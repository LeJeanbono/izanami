package domains.abtesting.impl

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import cats.data.OptionT
import domains.Key
import domains.abtesting._
import domains.events.EventStore
import domains.events.Events.ExperimentVariantEventCreated
import elastic.api._
import env.{DbDomainConfig, ElasticConfig}
import play.api.Logger
import play.api.libs.json.{JsObject, JsValue, Json}
import store.Result.Result
import store.{FindResult, Result, SourceFindResult}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

//////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////     ELASTIC      ////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////////
object ExperimentVariantEventElasticStore {

  def apply(elastic: Elastic[JsValue],
            elasticConfig: ElasticConfig,
            config: DbDomainConfig,
            eventStore: EventStore,
            actorSystem: ActorSystem): ExperimentVariantEventElasticStore =
    new ExperimentVariantEventElasticStore(elastic, elasticConfig, config, eventStore, actorSystem)
}

class ExperimentVariantEventElasticStore(client: Elastic[JsValue],
                                         elasticConfig: ElasticConfig,
                                         dbDomainConfig: DbDomainConfig,
                                         eventStore: EventStore,
                                         actorSystem: ActorSystem)
    extends ExperimentVariantEventStore {

  import elastic.implicits._
  import elastic.codec.PlayJson._
  import actorSystem.dispatcher

  private implicit val s   = actorSystem
  private implicit val mat = ActorMaterializer()
  private implicit val es  = eventStore

  private val esIndex        = dbDomainConfig.conf.namespace.replaceAll(":", "_")
  private val esType         = "type"
  private val displayedIndex = s"${esIndex}_counter_displayed"
  private val wonIndex       = s"${esIndex}_counter_won"

  private val counter = Json.parse("""
                                     |{
                                     |   "settings" : { "number_of_shards" : 1 },
                                     |   "mappings" : {
                                     |     "type" : {
                                     |       "properties" : {
                                     |         "counter" : { "type" : "long" }
                                     |       }
                                     |     }
                                     | }
                                     |}
                                   """.stripMargin)

  private val mapping = Json.parse(s"""
                                      |{
                                      |   "mappings" : {
                                      |     "$esType" : {
                                      |       "properties" : {
                                      |         "id": { "type" : "keyword" },
                                      |         "clientId": { "type" : "keyword" },
                                      |         "@type": { "type" : "keyword" },
                                      |         "variant": {
                                      |           "properties" : {
                                      |             "id": { "type" : "keyword" },
                                      |             "name": { "type" : "keyword" },
                                      |             "description": { "type" : "text" },
                                      |             "traffic": { "type" : "double" },
                                      |             "currentPopulation": { "type" : "integer" }
                                      |           }
                                      |         },
                                      |         "date": { "type": "date", "format" : "date_hour_minute_second_millis" },
                                      |         "transformation": { "type" : "double" },
                                      |         "experimentId": { "type" : "keyword" },
                                      |         "variantId": { "type" : "keyword" }
                                      |       }
                                      |     }
                                      | }
                                      |}
    """.stripMargin)

  Logger.info(s"Initializing index $esIndex with type $esType")
  Await.result(client.verifyIndex(esIndex).flatMap {
    case true =>
      FastFuture.successful(Done)
    case _ =>
      client.createIndex(esIndex, mapping)
  }, 3.seconds)

  Logger.info(s"Initializing index $displayedIndex with type type")
  Await.result(client.verifyIndex(displayedIndex).flatMap {
    case true =>
      FastFuture.successful(Done)
    case _ =>
      client.createIndex(displayedIndex, counter)
  }, 3.seconds)

  Logger.info(s"Initializing index $wonIndex with type type")
  Await.result(client.verifyIndex(wonIndex).flatMap {
    case true =>
      FastFuture.successful(Done)
    case _ =>
      client.createIndex(wonIndex, counter)
  }, 3.seconds)

  private val index     = client.index(esIndex / esType)
  private val displayed = client.index(displayedIndex / "type")
  private val won       = client.index(wonIndex / "type")

  private val incrUpdateQuery =
    Json.parse("""
                 |{
                 |    "script" : {
                 |        "inline": "ctx._source.counter += params.count",
                 |        "lang": "painless",
                 |        "params" : {
                 |            "count" : 1
                 |        }
                 |    },
                 |    "upsert" : {
                 |        "counter" : 1
                 |    }
                 |}
               """.stripMargin)

  private def incrWon(experimentId: String, variantId: String): Future[Done] = {
    val id = s"$experimentId.$variantId"
    won.update(incrUpdateQuery, id, retry_on_conflict = Some(5)).map(_ => Done)
  }

  private def incrDisplayed(experimentId: String, variantId: String): Future[Done] = {
    val id = s"$experimentId.$variantId"
    displayed
      .update(incrUpdateQuery, id, retry_on_conflict = Some(5))
      .map(_ => Done)
  }

  private def getWon(experimentId: String, variantId: String): Future[Long] = {
    val id = s"$experimentId.$variantId"
    won
      .get(id)
      .map { resp =>
        (resp._source \ "counter").as[Long]
      }
      .recover {
        case EsException(_, 404, _) => 0
      }
  }
  private def getDisplayed(experimentId: String, variantId: String): Future[Long] = {
    val id = s"$experimentId.$variantId"
    displayed
      .get(id)
      .map { resp =>
        (resp._source \ "counter").as[Long]
      }
      .recover {
        case EsException(_, 404, _) => 0
      }
  }
  private def incrAndGetDisplayed(experimentId: String, variantId: String): Future[Long] =
    incrDisplayed(experimentId, variantId)
      .flatMap { _ =>
        getDisplayed(experimentId, variantId)
      }
  private def incrAndGetWon(experimentId: String, variantId: String): Future[Long] =
    incrWon(experimentId, variantId)
      .flatMap { _ =>
        getWon(experimentId, variantId)
      }

  override def create(id: ExperimentVariantEventKey,
                      data: ExperimentVariantEvent): Future[Result[ExperimentVariantEvent]] = {
    val res: Future[Result[ExperimentVariantEvent]] = data match {
      case e: ExperimentVariantDisplayed =>
        for {
          displayed <- incrAndGetDisplayed(id.experimentId.key, id.variantId) // increment display counter
          won       <- getWon(id.experimentId.key, id.variantId)              // get won counter
          transformation = if (displayed != 0) (won * 100.0) / displayed
          else 0.0
          toSave = e.copy(transformation = transformation)
          result <- saveToEs(id, toSave) // add event
        } yield result
      case e: ExperimentVariantWon =>
        for {
          won       <- incrAndGetWon(id.experimentId.key, id.variantId) // increment won counter
          displayed <- getDisplayed(id.experimentId.key, id.variantId)  // get display counter
          transformation = if (displayed != 0) (won * 100.0) / displayed
          else 0.0
          toSave = e.copy(transformation = transformation)
          result <- saveToEs(id, toSave) // add event
        } yield result
    }
    res.andPublishEvent(e => ExperimentVariantEventCreated(id, e))
  }

  private def saveToEs(id: ExperimentVariantEventKey,
                       data: ExperimentVariantEvent): Future[Result[ExperimentVariantEvent]] =
    index
      .index[ExperimentVariantEvent](
        data,
        Some(id.id),
        refresh = elasticConfig.automaticRefresh
      )
      .map(_ => Result.ok(data))

  override def deleteEventsForExperiment(experiment: Experiment): Future[Result[Done]] =
    Source(experiment.variants.toList)
      .flatMapMerge(
        4, { v =>
          index.scroll(
            Json.obj(
              "query" -> Json.obj(
                "bool" -> Json.obj(
                  "must" -> Json.arr(
                    Json.obj("term" -> Json.obj("id"        -> experiment.id.key)),
                    Json.obj("term" -> Json.obj("variantId" -> v.id))
                  )
                )
              )
            ),
            "1s"
          )
        }
      )
      .mapConcat { _.hits.hits.map(_._id).toList }
      .map { id =>
        Bulk[ExperimentVariantEvent](BulkOpType(delete = Some(BulkOpDetail(None, None, Some(id)))), None)
      }
      .via(client.bulkFlow(batchSize = 500))
      .runWith(Sink.ignore)
      .map(_ => Result.ok(Done))

  private def aggRequest(experimentId: String, variant: String, interval: String): JsObject =
    Json.obj(
      "size" -> 0,
      "query" -> Json.obj(
        "bool" -> Json.obj(
          "must" -> Json.arr(
            Json.obj("term" -> Json.obj("experimentId" -> experimentId)),
            Json.obj("term" -> Json.obj("variantId"    -> variant))
          )
        )
      ),
      "aggs" -> Json.obj(
        "dates" -> Json.obj(
          "date_histogram" -> Json.obj(
            "field"    -> "date",
            "interval" -> interval
          ),
          "aggs" -> Json.obj(
            "events" -> Json.obj(
              "terms" -> Json.obj(
                "field" -> "@type"
              ),
              "aggs" -> Json.obj(
                "avg" -> Json.obj(
                  "avg" -> Json.obj(
                    "field" -> "transformation"
                  )
                )
              )
            )
          )
        )
      )
    )

  private def minOrMaxQuery(experimentId: String, order: String): Future[Option[LocalDateTime]] = {
    val query = Json.obj(
      "size"    -> 1,
      "_source" -> Json.arr("date"),
      "query"   -> Json.obj("term" -> Json.obj("id" -> Json.obj("value" -> experimentId))),
      "sort"    -> Json.arr(Json.obj("date" -> Json.obj("order" -> order)))
    )
    Logger.debug(s"Querying ${Json.prettyPrint(query)}")
    index
      .search(
        query
      )
      .map {
        case SearchResponse(_, _, _, hits, _, _) =>
          hits.hits.map(h => (h._source \ "date").as[LocalDateTime]).headOption
      }
  }

  private def max(experimentId: String): Future[Option[LocalDateTime]] =
    minOrMaxQuery(experimentId, "desc")
  private def min(experimentId: String): Future[Option[LocalDateTime]] =
    minOrMaxQuery(experimentId, "asc")

  private def calcInterval(experimentId: String): Future[String] = {
    import cats.instances.future._

    val minDate: Future[Option[LocalDateTime]] = min(experimentId)
    val maxDate: Future[Option[LocalDateTime]] = max(experimentId)

    (for {
      min <- OptionT(minDate)
      max <- OptionT(maxDate)
    } yield {
      if (ChronoUnit.MONTHS.between(min, max) > 50) {
        "month"
      } else if (ChronoUnit.WEEKS.between(min, max) > 50) {
        "week"
      } else if (ChronoUnit.DAYS.between(min, max) > 50) {
        "day"
      } else if (ChronoUnit.HOURS.between(min, max) > 50) {
        "hour"
      } else if (ChronoUnit.MINUTES.between(min, max) > 50) {
        "minute"
      } else {
        "second"
      }
    }).value.map(_.getOrElse("second"))
  }

  private def getVariantResult(experimentId: String, variant: Variant): Source[VariantResult, NotUsed] = {

    val variantId: String = variant.id

    val events: Source[Seq[ExperimentVariantEvent], NotUsed] = Source
      .fromFuture(calcInterval(experimentId))
      .mapAsync(1) { interval =>
        val query = aggRequest(experimentId, variantId, interval)
        Logger.debug(s"Querying ${Json.prettyPrint(query)}")
        index
          .search(query)
          .map {
            case SearchResponse(_, _, _, _, _, Some(aggs)) =>
              (aggs \ "dates" \ "buckets").as[Seq[JsObject]].flatMap { dates =>
                val date =
                  LocalDateTime.parse((dates \ "key_as_string").as[String], DateTimeFormatter.ISO_DATE_TIME)

                (dates \ "events" \ "buckets").as[Seq[JsObject]].map { event =>
                  val transformation =
                    (event \ "avg" \ "value").asOpt[Double].getOrElse(0d)
                  (event \ "key").as[String] match {
                    case "VariantDisplayedEvent" =>
                      ExperimentVariantDisplayed(
                        ExperimentVariantEventKey(Key(experimentId), variantId, "NA", "displayed", "NA"),
                        Key(experimentId),
                        "NA",
                        variant,
                        date,
                        transformation,
                        variantId
                      )
                    case "VariantWonEvent" =>
                      ExperimentVariantWon(ExperimentVariantEventKey(Key(experimentId), variantId, "NA", "won", "NA"),
                                           Key(experimentId),
                                           "NA",
                                           variant,
                                           date,
                                           transformation,
                                           variantId)
                  }
                }
              }
            case SearchResponse(_, _, _, hits, _, None) =>
              Seq.empty[ExperimentVariantEvent]
          }
      }

    val won: Source[Long, NotUsed] =
      Source.fromFuture(getWon(experimentId, variantId))
    val displayed: Source[Long, NotUsed] =
      Source.fromFuture(getDisplayed(experimentId, variantId))

    events.zip(won).zip(displayed).map {
      case ((e, w), d) =>
        VariantResult(
          variant = Some(variant),
          displayed = d,
          won = w,
          transformation = if (d != 0) (w * 100.0) / d else 0.0,
          events = e
        )
    }
  }

  override def findVariantResult(experiment: Experiment): FindResult[VariantResult] =
    SourceFindResult(
      Source(experiment.variants.toList)
        .flatMapMerge(4, v => getVariantResult(experiment.id.key, v))
    )

  override def listAll(patterns: Seq[String]) =
    index
      .scroll(Json.obj("query" -> Json.obj("match_all" -> Json.obj())))
      .mapConcat(s => s.hitsAs[ExperimentVariantEvent].toList)
      .filter(e => e.id.key.matchPatterns(patterns: _*))
}