/*
 * Copyright 2018-2019 scala-steward contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.scalasteward.core.model

import cats.implicits._
import eu.timepit.refined.W
import io.circe.{Decoder, Encoder}
import monocle.Lens
import org.scalasteward.core.model.Update.{Group, Single}
import org.scalasteward.core.util
import org.scalasteward.core.util.Nel
import org.scalasteward.core.util.string.MinLengthString
import scala.util.matching.Regex

sealed trait Update extends Product with Serializable {
  def groupId: String
  def artifactId: String
  def artifactIds: Nel[String]
  def currentVersion: String
  def newerVersions: Nel[String]

  def name: String =
    Update.nameOf(groupId, artifactId)

  def nextVersion: String =
    newerVersions.head

  def replaceAllInStrict: String => Option[String] =
    replaceAllInImpl(true, searchTerms.toList)

  def replaceAllIn: String => Option[String] =
    replaceAllInImpl(false, searchTerms.toList)

  def replaceAllInRelaxed: String => Option[String] =
    replaceAllInImpl(false, util.string.extractWords(artifactId))

  def replaceAllInSliding: String => Option[String] =
    replaceAllInImpl(false, artifactId.sliding(5).take(5).toList)

  def replaceAllInGroupId: String => Option[String] =
    replaceAllInImpl(
      false,
      groupId.split('.').toList.drop(1).flatMap(util.string.extractWords).filter(_.length > 3)
    )

  def replaceAllInImpl(includeGroupId: Boolean, terms: List[String]): String => Option[String] = {
    val ignoreChar = ".?"
    val quotedSearchTerms = terms
      .map { term =>
        Regex
          .quoteReplacement(Update.removeCommonSuffix(term))
          .replace(".", ignoreChar)
          .replace("-", ignoreChar)
      }
      .filter(term => term.nonEmpty && term =!= ignoreChar)

    if (quotedSearchTerms.nonEmpty) {
      val searchTerm = quotedSearchTerms.mkString_("(", "|", ")")
      val groupIdPattern = if (includeGroupId) s"$groupId.*?" else ""

      replaceVersion(s"(?i)(.*)($groupIdPattern$searchTerm.*?)${Regex.quote(currentVersion)}".r)
    } else { _ =>
      None
    }
  }

  private def replaceVersion(regex: Regex): String => Option[String] =
    target =>
      util.string.replaceSomeInOpt(
        regex,
        target,
        m => {
          val group1 = m.group(1)
          if (group1.toLowerCase.contains("previous") || group1.trim.startsWith("//"))
            None
          else
            Some(group1 + m.group(2) + nextVersion)
        }
      )

  def searchTerms: Nel[String] = {
    val terms = this match {
      case s: Single => s.artifactIds
      case g: Group  => g.artifactIds.concat(g.artifactIdsPrefix.map(_.value).toList)
    }
    terms.map(Update.nameOf(groupId, _))
  }

  def show: String = {
    val artifacts = this match {
      case s: Single => s.artifactId + s.configurations.fold("")(":" + _)
      case g: Group  => g.artifactIds.mkString_("{", ", ", "}")
    }
    val versions = (currentVersion :: newerVersions).mkString_("", " -> ", "")
    s"$groupId:$artifacts : $versions"
  }
}

object Update {
  final case class Single(
      groupId: String,
      artifactId: String,
      currentVersion: String,
      newerVersions: Nel[String],
      configurations: Option[String] = None
  ) extends Update {
    override def artifactIds: Nel[String] =
      Nel.one(artifactId)
  }

  object Single {
    val artifactIdLens: Lens[Update.Single, String] =
      Lens[Update.Single, String](_.artifactId)(artifactId => _.copy(artifactId = artifactId))
  }

  final case class Group(
      groupId: String,
      artifactIds: Nel[String],
      currentVersion: String,
      newerVersions: Nel[String]
  ) extends Update {
    override def artifactId: String = {
      val possibleMainArtifactIds = for {
        prefix <- artifactIdsPrefix.toList
        suffix <- commonSuffixes
      } yield prefix.value + suffix

      artifactIds
        .find(artifactId => possibleMainArtifactIds.contains(artifactId))
        .getOrElse(artifactIds.head)
    }

    def artifactIdsPrefix: Option[MinLengthString[W.`3`.T]] =
      util.string.longestCommonPrefixGreater[W.`3`.T](artifactIds)
  }

  ///

  def group(updates: List[Single]): List[Update] =
    updates
      .groupByNel(update => (update.groupId, update.currentVersion, update.newerVersions))
      .values
      .map { nel =>
        val head = nel.head
        val artifacts = nel.map(_.artifactId).distinct.sorted
        if (artifacts.tail.nonEmpty)
          Group(head.groupId, artifacts, head.currentVersion, head.newerVersions)
        else
          head
      }
      .toList
      .sortBy(update => (update.groupId, update.artifactId))

  val commonSuffixes: List[String] =
    List("config", "contrib", "core", "extra", "server")

  def removeCommonSuffix(str: String): String =
    util.string.removeSuffix(str, commonSuffixes)

  def nameOf(groupId: String, artifactId: String): String =
    if (commonSuffixes.contains(artifactId))
      util.string.rightmostLabel(groupId)
    else
      artifactId

  implicit val updateEncoder: Encoder[Update] =
    io.circe.generic.semiauto.deriveEncoder

  implicit val updateDecoder: Decoder[Update] =
    io.circe.generic.semiauto.deriveDecoder

  implicit val updateSingleEncoder: Encoder[Update.Single] =
    io.circe.generic.semiauto.deriveEncoder

  implicit val updateSingleDecoder: Decoder[Update.Single] =
    io.circe.generic.semiauto.deriveDecoder
}
