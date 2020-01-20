package ecosystem

import scala.language.implicitConversions
import collection.JavaConverters._

import better.files.File
import org.jline.reader.UserInterruptException

import org.eclipse.jgit.api._
import org.eclipse.jgit.lib._
import org.eclipse.jgit.api.errors.RefAlreadyExistsException
import org.eclipse.jgit.transport.URIish

import ecosystem.impl._
import ecosystem.model.{ given, _ }
import ecosystem.data.{ given, _ }
import ecosystem.rendering._


inline def (cmd: Command) execute(): Unit = executeCommand(cmd)
def executeCommand(cmd: Command): Unit =
  println(s">>> ${cmd}")
  cmd match
    case Show => projects.all.map(_.name).foreach(println)
    case Exit => throw UserInterruptException("")
    case Clone =>
      for p <- projects.all if !p.isCloned do Clone(p.name).execute()
    case Update =>
      for p <- projects.all do Update(p.name).execute()

    case Check =>
      UpdateDotty.execute()
      val ciTrackingCache = buildCiTrackingCache()
      val reportTableHeader = "Project" :: "Branch" :: "Ahead" :: "Behind" :: "CI Tracking" :: Nil
      val reportTableValues =
        for
          project <- projects.all
          report = checkProject(project, ciTrackingCache)
        yield
          val mainBranch     = checkPredicate(report.mainBranch, _ == "dotty-community-build")
          val aheadUpstream  = checkPredicate(report.aheadUpstream, _ == 0)
          val behindUpstream = checkPredicate(report.behindUpstream, _ == 0)
          val ciTracking     = if report.ciHash == report.originHeadHash then green("√") else red("X")
          project.name :: mainBranch :: aheadUpstream :: behindUpstream :: ciTracking :: Nil
      val reportTable: List[List[String]] = reportTableHeader :: reportTableValues
      println(Tabulator.format(reportTable))

    case UpdateDotty =>
      val git =
        if !dotty.isCloned
          out("Dotty is not cloned yet, cloning. This might take 1-2 minutes.")
          val git0 = Git.cloneRepository()
            .setURI(dotty.origin)
            .setDirectory(dotty.dir.toJava)
            .call()
          out("Initializing submodules.")
          git0.submoduleInit.call()
          git0
        else Git.open(dotty.dir.toJava)
      out("Pulling the latest Dotty changes")
      git.pull.call()
      out("Updating Dotty submodules. If working against a fresh clone, this might take a few minutes.")
      git.submoduleUpdate.call()
      git.close()

    case cmd: ProjectCommand =>
      val project =
        try cmd.projectName.asProject
        catch
          case _: NoSuchElementException => return println(s"Project not found: ${cmd.projectName}")
      if !project.isCloned && !cmd.isInstanceOf[Clone] then Clone(project.name).execute()

      cmd match
        case Show(name) =>
          out(s"""
            |Project: ${project.name}
            |Staging: ${project.origin}
            |Upstream: ${project.upstream}
            |Upstream branch: upstream/${project.upstreamBranch}
            |Dependencies: ${project.dependencies.map(_.name).mkString(", ")}
          """)

        case Clone(name) =>
          val git = Git.cloneRepository()
            .setURI(project.origin)
            .setDirectory(project.dir.toJava)
            .call()
          git.remoteAdd
            .setName("upstream")
            .setUri(URIish(project.upstream))
            .call()
          git.fetch
            .setRemote("upstream")
            .call()
          checkoutBranchIfNotExists(project.originBranch, git)
          git.close()

        case Update(name) =>
          project.withGit { git =>
            checkoutBranchIfNotExists(project.originBranch, git)
            git.pull.call()
            git.fetch.setRemote("upstream").call()
          }

        case Clean(name) => exec(project.cleanCommand, project.dir)

        case cmd: BuildCommand =>
          project.dependencies.foreach { dep => PublishLocal(dep.name, cmd.scalaVersion).execute() }

          cmd match
            case Compile(name, version) =>
              exec(project.compileCommand(version), project.dir)

            case Test(name, version) =>
              exec(project.testCommand(version), project.dir)

            case PublishLocal(name, version) =>
              exec(project.publishLocalCommand(version), project.dir)


        case Check(name) =>
          UpdateDotty.execute()
          val report = checkProject(project)

          out(s"""
            |Main branch: ${checkPredicate(report.mainBranch, _ == "dotty-community-build")}
            |Ahead upstream: ${checkPredicate(report.aheadUpstream, _ == 0)}
            |Behind upstream: ${checkPredicate(report.behindUpstream, _ == 0)}
            |CI hash == Origin head hash: ${checkPredicate((report.ciHash, report.originHeadHash), t => t._1 == t._2, t => s"${t._1} == ${t._2}")}
          """)

def checkoutBranchIfNotExists(branch: String, git: Git): Unit =
  if branch ne null
    println(yellow(s"[warn] A non-standard branch `$branch` was specified"))

    val createBranch =
      !git.branchList.call().asScala.exists(_.getName == Constants.R_HEADS + branch)

    var cmd = git.checkout
      .setCreateBranch(createBranch)
      .setName(branch)

    if createBranch then
      println(s"Branch $branch does not exist and will now be created")
      cmd = cmd
        .setStartPoint(s"origin/$branch")
        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)

    cmd.call()
