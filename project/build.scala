/**
 * Copyright 2011,2012 National ICT Australia Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import sbt._
import complete.DefaultParsers._
import Keys._
import com.typesafe.sbt._
import pgp.PgpKeys._
import sbt.Configuration
import SbtSite._
import scala.Some
import SiteKeys._
import SbtGit._
import GitKeys._
import SbtGhPages._
import GhPagesKeys._
import sbtrelease._
import ReleasePlugin._
import ReleaseKeys._
import ReleaseStateTransformations._
import ls.Plugin._
import LsKeys._
import Utilities._
import Defaults._

object build extends Build {
  type Settings = Project.Setting[_]

  lazy val scoobi = Project(
    id = "scoobi",
    base = file("."),
    settings = Defaults.defaultSettings ++
               scoobiSettings           ++
               dependencies.settings    ++
               compilationSettings      ++
               testingSettings          ++
               siteSettings             ++
               publicationSettings      ++
               notificationSettings     ++
               releaseSettings
  )

  lazy val scoobiVersion = SettingKey[String]("scoobi-version", "defines the current Scoobi version")
  lazy val scoobiSettings: Seq[Settings] = Seq(
    name := "scoobi",
    organization := "com.nicta",
    scoobiVersion in GlobalScope <<= version,
    scalaVersion := "2.10.2")

  lazy val compilationSettings: Seq[Settings] = Seq(
    (sourceGenerators in Compile) <+= (sourceManaged in Compile) map GenWireFormat.gen,
    scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-language:implicitConversions,reflectiveCalls,postfixOps,higherKinds,existentials"),
    scalacOptions in Test ++= Seq("-Yrangepos")
  )

  lazy val testingSettings: Seq[Settings] = Seq(
    testOptions := Seq(Tests.Filter(s => s.endsWith("Spec") || s.contains("guide") || Seq("Index", "All", "UserGuide", "ReadMe").exists(s.contains))),
    fork in Test := true,
    javaOptions in Test ++= Seq("-Xmx1g")
  )

  lazy val siteSettings: Seq[Settings] = ghpages.settings ++ SbtSite.site.settings ++ Seq(
    siteSourceDirectory <<= target (_ / "specs2-reports"),
    // depending on the version, copy the api files to a different directory
    siteMappings <++= (mappings in packageDoc in Compile, version) map { (m, v) =>
      for((f, d) <- m) yield (f, if (v.trim.endsWith("SNAPSHOT")) ("api/master/" + d) else ("api/SCOOBI-"+v+"/"+d))
    },
    // override the synchLocal task to avoid removing the existing files
    synchLocal <<= (privateMappings, updatedRepository, gitRunner, streams) map { (mappings, repo, git, s) =>
      val betterMappings = mappings map { case (file, target) => (file, repo / target) }
      IO.copy(betterMappings)
      repo
    },
    gitRemoteRepo := "git@github.com:NICTA/scoobi.git"
  )

  lazy val notificationSettings: Seq[Settings] = lsSettings ++ Seq(
    (LsKeys.ghBranch in LsKeys.lsync) := Some("master-publish"),
    (LsKeys.ghUser in LsKeys.lsync) := Some("nicta"),
    (LsKeys.ghRepo in LsKeys.lsync) := Some("scoobi")
  )

  lazy val publicationSettings: Seq[Settings] = Seq(
    publishTo <<= version { v: String =>
      val nexus = "https://oss.sonatype.org/"
      if (v.trim.endsWith("SNAPSHOT")) Some("snapshots" at nexus + "content/repositories/snapshots")
      else                             Some("staging" at nexus + "service/local/staging/deploy/maven2")
    },
    publishMavenStyle := true,
    publishArtifact in Test := false,
    pomIncludeRepository := { x => false },
    pomExtra := (
      <url>http://nicta.github.io/scoobi</url>
      <licenses>
        <license>
          <name>Apache 2.0</name>
          <url>http://www.opensource.org/licenses/Apache-2.0</url>
          <distribution>repo</distribution>
        </license>
      </licenses>
      <scm>
        <url>http://github.com/NICTA/scoobi</url>
        <connection>scm:http:http://NICTA@github.com/NICTA/scoobi.git</connection>
      </scm>
      <developers>
        <developer>
          <id>blever</id>
          <name>Ben Lever</name>
          <url>http://github.com/blever</url>
        </developer>
         <developer>
          <id>espringe</id>
          <name>Eric Springer</name>
          <url>http://github.com/espringe</url>
        </developer>
        <developer>
          <id>etorreborre</id>
          <name>Eric Torreborre</name>
          <url>http://etorreborre.blogspot.com/</url>
        </developer>
      </developers>
    ),
    credentials := Seq(Credentials(Path.userHome / ".sbt" / "scoobi.credentials"))
  )

  /**
   * RELEASE PROCESS
   */
  lazy val releaseSettings =
    ReleasePlugin.releaseSettings ++ Seq(
    tagName <<= (version in ThisBuild) map (v => "SCOOBI-" + v),
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      setReleaseVersion,
      commitReleaseVersion,
      updateLicences,
      generateIndex,
      generateReadMe,
      publishSite,
      publishSignedArtifacts,
      publishForCDH3,
      notifyLs,
      notifyHerald,
      tagRelease,
      setNextVersion,
      commitNextVersion,
      pushChanges
    ),
    releaseSnapshotProcess := Seq[ReleaseStep](
      generateUserGuide,
      publishSite,
      publishSignedArtifacts,
      publishForCDH3),
    commands += releaseSnapshotCommand
  ) ++
  Seq(publishUserGuideTask <<= pushSite.dependsOn(makeSite).dependsOn(generateUserGuideTask)) ++
  documentationSettings

  lazy val releaseSnapshotProcess = SettingKey[Seq[ReleaseStep]]("release-snapshot-process")
  private lazy val releaseSnapshotCommandKey = "release-snapshot"
  private val WithDefaults = "with-defaults"
  private val SkipTests = "skip-tests"
  private val releaseSnapshotParser = (Space ~> WithDefaults | Space ~> SkipTests).*

  val releaseSnapshotCommand: Command = Command(releaseSnapshotCommandKey)(_ => releaseSnapshotParser) { (st, args) =>
    val extracted = Project.extract(st)
    val releaseParts = extracted.get(releaseSnapshotProcess)

    val startState = st
      .put(useDefaults, args.contains(WithDefaults))
      .put(skipTests, args.contains(SkipTests))

    val initialChecks = releaseParts.map(_.check)
    val process = releaseParts.map(_.action)

    initialChecks.foreach(_(startState))
    Function.chain(process)(startState)
  }

  /**
   * DOCUMENTATION
   */
  lazy val documentationSettings =
    testTaskDefinition(generateUserGuideTask, Seq(Tests.Filter(_.endsWith("UserGuide")), Tests.Argument("html"))) ++
    testTaskDefinition(generateReadMeTask, Seq(Tests.Filter(_.endsWith("ReadMe")), Tests.Argument("markdown"))) ++
    testTaskDefinition(generateIndexTask, Seq(Tests.Filter(_.endsWith("Index")), Tests.Argument("html", "checkurls")))

  lazy val updateLicences = ReleaseStep { st =>
    st.log.info("Updating the license headers")
    "mvn license:format" !! st.log
    commitCurrent("added license headers where missing")(st)
  }

  lazy val generateUserGuideTask = TaskKey[Tests.Output]("generate-user-guide", "generate the user guide")
  lazy val generateUserGuide     = ReleaseStep { st: State =>
    val st2 = executeStepTask(generateUserGuideTask, "Generating the User Guide", Test)(st)
    commitCurrent("updated the UserGuide")(st2)
  }

  lazy val generateReadMeTask = TaskKey[Tests.Output]("generate-readme", "generate the README")
  lazy val generateReadMe     = ReleaseStep { st: State =>
    val st2 = executeStepTask(generateReadMeTask, "Generating the README file", Test)(st)
    IO.copyFile(file("target/specs2-reports/README.md"), file("README.md"))
    commitCurrent("updated the README file")(st2)
  }

  lazy val generateIndexTask = TaskKey[Tests.Output]("generate-index", "generate the index page, the User Guide and check the User Guide urls")
  lazy val generateIndex     = executeStepTask(generateIndexTask, "Generating the index, the User Guide and checking the urls of the User Guide", Test)

  lazy val publishUserGuideTask = TaskKey[Unit]("publish-user-guide", "publish the user guide")

  lazy val publishSite = ReleaseStep { st: State =>
    val st2 = executeStepTask(makeSite, "Making the site")(st)
    executeStepTask(pushSite, "Publishing the site")(st2)
  }

  def testTaskDefinition(task: TaskKey[Tests.Output], options: Seq[TestOption]) =
    Seq(testTask(task))                          ++
    inScope(GlobalScope)(defaultTestTasks(task)) ++
    inConfig(Test)(testTaskOptions(task))        ++
    (testOptions in (Test, task) ++= options)

  def testTask(task: TaskKey[Tests.Output]) =
    task <<= (streams in Test, loadedTestFrameworks in Test, testLoader in Test,
      testGrouping in Test in test, testExecution in Test in task,
      fullClasspath in Test in test, javaHome in test) flatMap Defaults.allTestGroupsTask

  /**
   * PUBLICATION
   */
  lazy val publishSignedArtifacts = executeStepTask(publishSigned, "Publishing signed artifacts")

  lazy val publishForCDH3 = ReleaseStep { st: State =>
    val extracted = Project.extract(st)
    val ref: ProjectRef = extracted.get(thisProjectRef)
    val st2 = extracted.append(List(version in ThisBuild in ref ~= { (v: String) => if (v.contains("SNAPSHOT")) v.replace("SNAPSHOT", "")+"cdh3-SNAPSHOT" else v+"-cdh3" }), st)
    executeTask(publishSigned, "Publishing CDH3 signed artifacts")(st2)
  }

  /**
   * NOTIFICATION
   */
  lazy val notifyLs = ReleaseStep { st: State =>
    val st2 = executeTask(writeVersion, "Writing ls.implicit.ly dependencies")(st)
    val st3 = commitCurrent("Added a new ls file")(st2)
    val st4 = pushCurrent(st3)
    executeTask(lsync, "Synchronizing with the ls.implict.ly website")(st4)
  }
  lazy val notifyHerald = ReleaseStep (
    action = (st: State) => {
      Process("herald &").lines; st.log.info("Starting herald to publish the release notes")
      commitCurrent("Updated the release notes")(st)
    },
    check  = (st: State) => {
      st.log.info("Checking if herald is installed")
      if ("which herald".!<(st.log) != 0) sys.error("You must install 'herald': http://github.com/n8han/herald on your machine")
      st
    }
  )

  /**
   * UTILITIES
   */
  private def executeStepTask(task: TaskKey[_], info: String) = ReleaseStep { st: State =>
    executeTask(task, info)(st)
  }

  private def executeTask(task: TaskKey[_], info: String) = (st: State) => {
    st.log.info(info)
    val extracted = Project.extract(st)
    val ref: ProjectRef = extracted.get(thisProjectRef)
    extracted.runTask(task in ref, st)._1
  }

  private def executeStepTask(task: TaskKey[_], info: String, configuration: Configuration) = ReleaseStep { st: State =>
    executeTask(task, info, configuration)(st)
  }

  private def executeTask(task: TaskKey[_], info: String, configuration: Configuration) = (st: State) => {
    st.log.info(info)
    val extracted = Project.extract(st)
    val ref: ProjectRef = extracted.get(thisProjectRef)
    extracted.runTask(task in configuration in ref, st)._1
  }

  private def commitCurrent(commitMessage: String): State => State = { st: State =>
    vcs(st).add(".") !! st.log
    val status = (vcs(st).status !!) trim

    if (status.nonEmpty) {
      vcs(st).commit(commitMessage) ! st.log
      st
    } else st
  }

  private def pushCurrent: State => State = { st: State =>
    vcs(st).pushChanges !! st.log
    st
  }

  private def vcs(st: State): Vcs = {
    st.extract.get(versionControlSystem).getOrElse(sys.error("Aborting release. Working directory is not a repository of a recognized VCS."))
  }

}


