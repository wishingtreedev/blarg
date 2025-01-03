package commands.build

import dev.wishingtree.branch.friday.Json.{JsonArray, JsonObject, JsonString}
import dev.wishingtree.branch.friday.{Json, JsonCodec, JsonEncoder}
import dev.wishingtree.branch.macaroni.fs.PathOps.*
import dev.wishingtree.branch.macaroni.poolers.ResourcePool
import dev.wishingtree.branch.piggy.{ResultSetGetter, Sql}
import dev.wishingtree.branch.piggy.Sql.{ps, tuple1}
import org.jsoup.Jsoup
import repository.{IndexedHtml, IndexedHtmlRepository}

import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager, ResultSet}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

trait Indexer {
  def index(): Unit
}

case class StaticIndexer(root: Path) extends Indexer {
  val _thisBuild: Path = root.getParent / "build"

  override def index(): Unit = {

    val loader: ContentLoader = ContentLoader(_thisBuild)

    val indexedData: mutable.ArrayBuffer[IndexedHtml] =
      mutable.ArrayBuffer.empty

    Files
      .walk(_thisBuild)
      .filter(_.toString.endsWith(".html"))
      .forEach { f =>
        val rawContent = loader.load(f)
        val html       = Jsoup.parse(rawContent)
        val paragraphs = html.getElementById("site-content").select("p")
        val content    =
          paragraphs.iterator().asScala.map(_.text()).mkString(" ")

        val tags = html
          .select("meta[name=keywords]")
          .attr("content")
          .split(",")
          .toList
          .filterNot(_.isBlank)

        indexedData.addOne(
          IndexedHtml(
            title = html.title(),
            description = html.select("meta[name=description]").attr("content"),
            tags = tags,
            content = content.toLowerCase,
            href = "/" + f.relativeTo(_thisBuild).toString,
            published = "", // TODO
            updated = "",   // TODO
            summary = content.take(250) + "..."
          )
        )

      }

    Files.writeString(
      _thisBuild / "search.json",
      JsonArray(indexedData.map(_.toJson).toIndexedSeq).toJsonString
    )

    val tagList = indexedData
      .flatMap(_.tags)
      .distinct
      .sorted
      .map { tag =>
        Json.obj(
          "tag"  -> JsonString(tag),
          "docs" -> Json.arr(
            indexedData
              .filter(_.tags.contains(tag))
              .map { d =>
                Json.obj(
                  "title"       -> JsonString(d.title),
                  "description" -> JsonString(d.description),
                  "href"        -> JsonString(d.href),
                  "published"   -> JsonString(d.published),
                  "updated"     -> JsonString(d.updated),
                  "tags"        -> JsonArray(
                    d.tags.map(JsonString.apply).toIndexedSeq
                  ),
                  "summary"     -> JsonString(d.content.take(250) + "...")
                )
              }
              .toSeq*
          )
        )
      }
      .toIndexedSeq

    Files.writeString(
      _thisBuild / "tags.json",
      JsonArray(tagList).toJsonString
    )
  }
}

case class ServerIndexer(root: Path) extends Indexer {
  val _thisBuild: Path = root.getParent / "build"

  given connPool: ResourcePool[Connection] =
    IndexedHtmlRepository.connPool(_thisBuild)

  override def index(): Unit = {
    val loader: ContentLoader = ContentLoader(_thisBuild)
    IndexedHtmlRepository.init.executePool
    Files
      .walk(_thisBuild)
      .filter(_.toString.endsWith(".html"))
      .forEach { f =>
        val rawContent = loader.load(f)
        val html       = Jsoup.parse(rawContent)
        val paragraphs = html.getElementById("site-content").select("p")
        val content    =
          paragraphs.iterator().asScala.map(_.text()).mkString(" ")

        val tags = html
          .select("meta[name=keywords]")
          .attr("content")
          .split(",")
          .toList
          .filterNot(_.isBlank)

        val record =
          IndexedHtml(
            title = html.title(),
            description = html.select("meta[name=description]").attr("content"),
            tags = tags,
            content = content.toLowerCase,
            href = "/" + f.relativeTo(_thisBuild).toString,
            published = "", // TODO
            updated = "",   // TODO
            summary = content.take(250) + "..."
          )

        IndexedHtmlRepository.ingest(record).executePool

      }

  }
}

object Indexer {

  def staticIndexer(root: Path): Indexer =
    StaticIndexer(root)

  def serverIndexer(root: Path): Indexer =
    ServerIndexer(root)

}
