package com.navneetgupta.cqrs.shared.readstore

import spray.json._
import akka.pattern._
import scala.concurrent.{ ExecutionContext, Future }
import com.typesafe.config.Config
import akka.actor._
import java.nio.charset.Charset
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.Materializer
import akka.http.scaladsl.unmarshalling.Unmarshaller
import scala.reflect.ClassTag
import com.navneetgupta.cqrs.shared.json.BaseJsonProtocol
import com.navneetgupta.cqrs.shared.actor.BaseActor

object ElasticsearchApi extends BaseJsonProtocol {
  trait EsResponse
  case class ShardData(total: Int, failed: Int, successful: Int)
  case class IndexingResult(_shards: ShardData, _index: String,
                            _type: String, _id: String, _version: Int,
                            created: Option[Boolean]) extends EsResponse
  case class UpdateScript(source: String, params: Map[String, Any])
  case class UpdateRequest(script: UpdateScript)
  case class SearchHit(_source: JsObject)
  case class QueryHits(hits: List[SearchHit])
  case class QueryResponse(hits: QueryHits) extends EsResponse
  case class DeleteResult(acknowledged: Boolean) extends EsResponse

  implicit val shardDataFormat = jsonFormat3(ShardData)
  implicit val indexResultFormat = jsonFormat6(IndexingResult)
  implicit val updateScriptFormat = jsonFormat2(UpdateScript)
  implicit val updateRequestFormat = jsonFormat1(UpdateRequest)
  implicit val searchHitFormat = jsonFormat1(SearchHit)
  implicit val queryHitsFormat = jsonFormat1(QueryHits)
  implicit val queryResponseFormat = jsonFormat1(QueryResponse)
  implicit val deleteResultFormat = jsonFormat1(DeleteResult)
}

trait ElasticsearchSupport { me: BaseActor =>

  import ElasticsearchApi._
  val esSettings = ElasticsearchSettings(context.system)
  def indexRoot: String
  def entityType: String
  def baseUrl = s"${esSettings.rootUrl}/${indexRoot}/$entityType"

  def callElasticsearch[RT: ClassTag](req: HttpRequest)(implicit ec: ExecutionContext, mater: Materializer, unmarshaller: Unmarshaller[ResponseEntity, RT]): Future[RT] = {
    log.info("====================================================")
    log.info("Call ElasticSearch Request is {}", req)
    Http(context.system).
      singleRequest(req).
      flatMap {
        case resp if resp.status.isSuccess =>
          Unmarshal(resp.entity).to[RT]
        case resp =>
          resp.discardEntityBytes()
          Future.failed(new RuntimeException(s"Unexpected status code of: ${resp.status}"))
      }
  }

  def queryElasticsearch[RT](query: String)(implicit ec: ExecutionContext, mater: Materializer, jf: RootJsonFormat[RT]): Future[List[RT]] = {
    val req = HttpRequest(HttpMethods.GET, Uri(s"$baseUrl/_search").withQuery(Uri.Query(("q", query))))
    log.info("====================================================")
    log.info("Query ElasticSearch Request is {}", req)
    callElasticsearch[QueryResponse](req).
      map(resp => {
        resp.hits.hits.map(_._source.convertTo[RT])
      })
  }

  def updateIndex[RT](id: String, request: RT, version: Option[Long])(implicit ec: ExecutionContext, jf: JsonFormat[RT], mater: Materializer): Future[IndexingResult] = {
    val urlBase = s"$baseUrl/$id"
    val requestUrl = version match {
      case None    => urlBase
      case Some(v) => s"$urlBase/_update?version=$v"
    }
    val entity = HttpEntity(ContentTypes.`application/json`, request.toJson.prettyPrint)
    val req = HttpRequest(HttpMethods.POST, requestUrl, entity = entity)
    callElasticsearch[IndexingResult](req)
  }

  def clearIndex(implicit ec: ExecutionContext, mater: Materializer) = {
    val req = HttpRequest(HttpMethods.DELETE, s"${esSettings.rootUrl}/${indexRoot}/")
    callElasticsearch[DeleteResult](req)
  }

}

class ElasticsearchSettingsImpl(conf: Config) extends Extension {
  val esConfig = conf.getConfig("elasticsearch")
  val host = esConfig.getString("host")
  val port = esConfig.getInt("port")
  val rootUrl = s"http://$host:$port"
}

object ElasticsearchSettings extends ExtensionId[ElasticsearchSettingsImpl] with ExtensionIdProvider {
  override def lookup = ElasticsearchSettings
  override def createExtension(system: ExtendedActorSystem) =
    new ElasticsearchSettingsImpl(system.settings.config)
}
