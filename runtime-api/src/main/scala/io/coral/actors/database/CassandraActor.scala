package io.coral.actors.database

import akka.actor.Props
import com.datastax.driver.core._

//json goodness
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods.render

// coral
import io.coral.actors.{SimpleEmitTrigger, CoralActor}

import scala.collection.mutable.{ListBuffer => mList}

object CassandraActor {
    implicit val formats = org.json4s.DefaultFormats

    def getParams(json: JValue) = {
        for {
            seeds <- (json \ "attributes" \ "params" \ "seeds").extractOpt[List[String]]
            keyspace <- (json \ "attributes" \ "params" \ "keyspace").extractOpt[String]
        } yield {
            (seeds, (json \ "attributes" \ "params" \ "port").extractOpt[Int], keyspace)
        }
    }

    def apply(json: JValue): Option[Props] = {
        getParams(json).map(_ => Props(classOf[CassandraActor], json))
    }
}

class CassandraActor(json: JObject) extends CoralActor(json)
    with CassandraHelper
    with SimpleEmitTrigger {

    var (seeds, port, keyspace) = CassandraActor.getParams(json).get

    override def preStart() {
        ensureConnection(seeds, port, keyspace)
    }

    var cluster: Cluster = _
    var session: Session = _
    var schema: JValue = _
    override def state = Map(
        ("connected", render(session != null && !session.isClosed)),
        ("keyspace", render(keyspace)),
        ("schema", render(getSchema(session, keyspace)))
    )

    override def simpleEmitTrigger(json: JObject) = {
        ensureConnection(seeds, port, keyspace)

        val queryResult = try {
            val query = (json \ "query").extractOpt[String].get.trim()

            // Since there is no way of using the AST from the query,
            // we use text parsing instead
            val result = if (query.startsWith("use keyspace")) {
                log.info("Changing keyspace, updating schema")
                keyspace = query.substring(13, query.length - 1)
                ensureConnection(seeds, port, keyspace)
                getSchema(session, keyspace)
                None
            } else {
                val data = session.execute(query)

                if (query.startsWith("select")) {
                    Some(data)
                } else {
                    None
                }
            }

            QueryResult(result, query, "")
        } catch {
            // In this case, the operation failed
            case e: Exception =>
                QueryResult(None, (json \ "query").extractOrElse[String](""), e.getMessage)
        }

        Some(determineResult(queryResult))
    }

    def determineResult(queryResult: QueryResult): JValue = {
        queryResult.result match {
            case Some(data) =>
                // Actual data returned
                render(("query" -> queryResult.lastQuery) ~ ("success" -> true) ~ renderResultSet(data))
            case None if (queryResult.lastError != "") =>
                // Error, and therefore no results
                render(("query" -> queryResult.lastQuery) ~ ("success" -> false) ~ ("error" -> queryResult.lastError))
            case None =>
                // No error, just no results
                render(("query" -> queryResult.lastQuery) ~ ("success" -> true))
            case _ =>
                JNothing
        }
    }

    /**
     * Ensure a connection to Cassandra. If already connected,
     * do nothing. If the given keyspace is not the same as the
     * keyspace of the session, reconnect to the new keyspace.
     * @param seeds The seed nodes to connect to
     * @param port The port to use, None to use the default port
     * @param keyspace The keyspace to connect to
     */
    def ensureConnection(seeds: List[String], port: Option[Int], keyspace: String) {
        // No need to connect if already having a valid session object
        if (session == null || session.isClosed || session.getLoggedKeyspace != keyspace) {
            log.info("CassandraActor not yet connected. Connecting now...")
            cluster = createCluster(seeds, port)
            session = cluster.connect(keyspace)
            schema = getSchema(session, keyspace)
        }
    }

    private def createCluster(seeds: List[String], port: Option[Int]): Cluster = {
        val builder = Cluster.builder()
        if (port.isDefined) {
            builder.withPort(port.get)
        }
        builder.addContactPoints(seeds: _*)
        builder.build()
    }
}

case class QueryResult(result: Option[ResultSet], lastQuery: String, lastError: String)

trait CassandraHelper {
    /**
     * Returns a list of all table names currently in the keyspace
     * @param session The session object to connect with
     * @param keyspace The name of the keyspace
     * @return A list of all table names in the keyspace
     */
    def getTables(session: Session, keyspace: String): List[String] = {
        val result = scala.collection.mutable.ListBuffer.empty[String]

        val tablemetadata = session.getCluster.getMetadata
            .getKeyspace(keyspace).getTables.iterator()

        while (tablemetadata.hasNext) {
            result += tablemetadata.next().getName
        }

        result.toList
    }

    /**
     * Method that returns a JSON representation of a schema
     * from a Cassandra keyspace.
     * @param session The session object to get the information with
     * @param keyspace The keyspace to get the schema from
     * @return A JSON representation of the schema of that keyspace
     */
    def getSchema(session: Session, keyspace: String): JValue = {
        var result = mList.empty[JObject]
        val tables = session.getCluster.getMetadata.getKeyspace(keyspace).getTables
        val tableIt = tables.iterator()

        while (tableIt.hasNext) {
            var tableDef = mList.empty[JObject]
            val table = tableIt.next()
            val tableName = table.getName
            val columns = table.getColumns
            val columnIt = columns.iterator()

            while (columnIt.hasNext) {
                val column = columnIt.next()
                val name = column.getName
                val datatype = column.getType.getName.toString
                tableDef += (name -> datatype)
            }

            result += (tableName -> tableDef)
        }

        JArray(result.toList)
    }

    /**
     * Method that creates a JObject from a Cassandra ResultSet.
     * @param rs The ResultSet
     * @return A JObject representation of the ResultSet with
     *         the following format:
                {
                    "name": "table1"
                    "columns": { [ column definition, ... ] }
                    "data":
                    [
                        [ 11, "blabla", 10.343 ],
                        [ 20, "wefiojewfo", 63.8127 ]
                    ]
                }
     */
    def renderResultSet(rs: ResultSet): JObject = {
        val it = rs.iterator()
        val columns: ColumnDefinitions = rs.getColumnDefinitions
        var result = mList.empty[JArray]

        while (it.hasNext) {
            val r = it.next
            var row = mList.empty[JsonAST.JValue]

            for (i <- 0 until columns.size) {
                val colType = columns.getType(i)

                // See http://www.datastax.com/documentation/developer
                //      /java-driver/1.0/java-driver/reference/javaClass2Cql3Datatypes_r.html

                // This list is not complete
                val value = colType.getName.toString match {
                    case "ascii" => JString(r.getString(i))
                    case "bigint" => JInt(r.getLong(i))
                    case "boolean" => JBool(r.getBool(i))
                    case "int" => JInt(r.getInt(i))
                    case "decimal" => JDecimal(r.getDecimal(i))
                    case "double" => JDouble(r.getDouble(i))
                    case "float" => JDouble(r.getFloat(i))
                    case "text" => JString(r.getString(i))
                    case "varchar" => JString(r.getString(i))
                    case "varint" => JInt(r.getLong(i))
                }

                row += value
            }

            result += JArray(row.toList)
        }

        val columnDef = getColumnDef(columns)
        val data = JArray(result.toList)
        columnDef ~ ("data" -> data)
    }

    /**
     * Method to get the list of column definitions ("name" -> "type")
     * in JSON format from a Cassandra ColumnDefinitions object
     * @param columns The columns from the ResultSet that Cassandra returned
     * @return A JSON object with  an array of ("name" -> "type") pairs
     */
    def getColumnDef(columns: ColumnDefinitions): JObject = {
        val result = mList.empty[JValue]
        val it = columns.iterator()

        while (it.hasNext) {
            val coldef = it.next
            val name = coldef.getName
            val coltype = coldef.getType.getName.toString
            result += JField(name, JString(coltype))
        }

        ("columns" -> JArray(result.toList))
    }
}