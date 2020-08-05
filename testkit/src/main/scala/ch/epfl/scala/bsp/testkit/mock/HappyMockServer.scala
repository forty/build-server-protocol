package ch.epfl.scala.bsp.testkit.mock

import java.io.File
import java.net.URI
import java.nio.file.Paths
import java.util
import java.util.UUID
import java.util.concurrent.CompletableFuture

import ch.epfl.scala.bsp.testkit.mock.HappyMockServer.ProtocolError
import ch.epfl.scala.bsp4j._

import scala.compat.java8.FunctionConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

object HappyMockServer {
  case object ProtocolError
}

/** Mock server that gives a happy successful result to any request. */
class HappyMockServer(base: File) extends AbstractMockServer {

  override var client: BuildClient = _

  val isInitialized: Promise[Either[ProtocolError.type, Unit]] =
    scala.concurrent.Promise[Either[ProtocolError.type, Unit]]()
  val isShutdown: Promise[Either[ProtocolError.type, Unit]] =
    scala.concurrent.Promise[Either[ProtocolError.type, Unit]]()

  // for easy override of individual parts of responses
  def name = "BSP Mock Server"
  def serverVersion = "1.0"
  def bspVersion = "2.0"

  def supportedLanguages: util.List[String] = List("java", "scala").asJava

  def capabilities: BuildServerCapabilities = {
    val c = new BuildServerCapabilities()
    c.setCompileProvider(new CompileProvider(supportedLanguages))
    c.setTestProvider(new TestProvider(supportedLanguages))
    c.setRunProvider(new RunProvider(supportedLanguages))
    c.setInverseSourcesProvider(true)
    c.setDependencySourcesProvider(true)
    c.setResourcesProvider(true)
    c.setBuildTargetChangedProvider(true)
    c.setJvmRunEnvironmentProvider(true)
    c.setJvmTestEnvironmentProvider(true)
    c
  }

  val baseUri: URI = base.getCanonicalFile.toURI
  private val languageIds = List("scala").asJava

  val targetId1 = new BuildTargetIdentifier(baseUri.resolve("target1").toString)
  val targetId2 = new BuildTargetIdentifier(baseUri.resolve("target2").toString)
  val targetId3 = new BuildTargetIdentifier(baseUri.resolve("target3").toString)
  val target1 = new BuildTarget(
    targetId1,
    List(BuildTargetTag.LIBRARY).asJava,
    languageIds,
    List.empty.asJava,
    new BuildTargetCapabilities(true, false, false)
  )
  val target2 = new BuildTarget(
    targetId2,
    List(BuildTargetTag.TEST).asJava,
    languageIds,
    List(targetId1).asJava,
    new BuildTargetCapabilities(true, true, false)
  )

  val target3 = new BuildTarget(
    targetId3,
    List(BuildTargetTag.APPLICATION).asJava,
    languageIds,
    List(targetId1).asJava,
    new BuildTargetCapabilities(true, false, true)
  )

  val compileTargets: Map[BuildTargetIdentifier, BuildTarget] = Map(
    targetId1 -> target1,
    targetId2 -> target2,
    targetId3 -> target3
  )

  def uriInTarget(target: BuildTargetIdentifier, filePath: String): URI =
    new URI(targetId1.getUri).resolve(filePath)

  private def asDirUri(path: URI): String =
    path.toString + "/"

  private def completeFuture[T](t: T): CompletableFuture[T] = {
    val ret = new CompletableFuture[T]()
    ret.complete(t)
    ret
  }

  private def environmentItem(testing: Boolean) = {
    val classpath = List("scala-library.jar").asJava
    val jvmOptions = List("-Xms256m").asJava
    val environmentVariables = Map("A" -> "a", "TESTING" -> testing.toString).asJava
    val workdir = "/tmp"
    val item1 = new JvmEnvironmentItem(
      targetId1,
      classpath,
      jvmOptions,
      workdir,
      environmentVariables
    )
    item1
  }
  override def jvmRunEnvironment(
      params: JvmRunEnvironmentParams
  ): CompletableFuture[JvmRunEnvironmentResult] =
    handleRequest(() => {
      val item1: JvmEnvironmentItem = environmentItem(testing = false)
      val result = new JvmRunEnvironmentResult(List(item1).asJava)
      result
    })

  override def jvmTestEnvironment(
      params: JvmTestEnvironmentParams
  ): CompletableFuture[JvmTestEnvironmentResult] =
    handleRequest(() => {
      val item1: JvmEnvironmentItem = environmentItem(testing = true)
      val result = new JvmTestEnvironmentResult(List(item1).asJava)
      result
    })

  override def buildTargetScalacOptions(
      params: ScalacOptionsParams
  ): CompletableFuture[ScalacOptionsResult] =
    handleRequest(() => {
      val options = List.empty[String].asJava
      val classpath = List("scala-library.jar").asJava
      val item1 =
        new ScalacOptionsItem(targetId1, options, classpath, uriInTarget(targetId1, "out").toString)
      val item2 =
        new ScalacOptionsItem(targetId2, options, classpath, uriInTarget(targetId2, "out").toString)
      val item3 =
        new ScalacOptionsItem(targetId3, options, classpath, uriInTarget(targetId3, "out").toString)
      val result = new ScalacOptionsResult(List(item1, item2, item3).asJava)
      result
    })

  override def buildTargetScalaTestClasses(
      params: ScalaTestClassesParams
  ): CompletableFuture[ScalaTestClassesResult] =
    handleRequest(() => {
      // TODO return some test classes
      val result = new ScalaTestClassesResult(List.empty.asJava)
      result
    })

  override def buildTargetScalaMainClasses(
      params: ScalaMainClassesParams
  ): CompletableFuture[ScalaMainClassesResult] =
    handleRequest(() => {
      val result = new ScalaMainClassesResult(List.empty.asJava)
      result
    })

  override def buildInitialize(
      params: InitializeBuildParams
  ): CompletableFuture[InitializeBuildResult] = {
    handleRequest(() => {
      val result = new InitializeBuildResult("BSP Mock Server", "1.0", "2.0", capabilities)
      result
    }, isBuildInitialize = true)
  }

  override def onBuildInitialized(): Unit =
    handleRequest(() => isInitialized.success(Right(())), isBuildInitialize = true)

  override def buildShutdown(): CompletableFuture[AnyRef] = {
    handleRequest(() => {
      isShutdown.success(Right())
      "boo"
    }, isBuildShutdown = true)
  }

  override def onBuildExit(): Unit = {
    Await.ready(isShutdown.future, 1.seconds)
  }

  override def workspaceBuildTargets(): CompletableFuture[WorkspaceBuildTargetsResult] =
    handleRequest(() => {
      val javaHome = sys.props.get("java.home").map(p => Paths.get(p).toUri.toString)
      val javaVersion = sys.props.get("java.vm.specification.version")
      val jvmBuildTarget = new JvmBuildTarget(javaHome.get, javaVersion.get)
      val scalaJars = List("scala-compiler.jar", "scala-reflect.jar", "scala-library.jar").asJava
      val scalaBuildTarget =
        new ScalaBuildTarget("org.scala-lang", "2.12.7", "2.12", ScalaPlatform.JVM, scalaJars)
      scalaBuildTarget.setJvmBuildTarget(jvmBuildTarget)

      target1.setDisplayName("target 1")
      target1.setBaseDirectory(targetId1.getUri)
      target1.setDataKind(BuildTargetDataKind.SCALA)
      target1.setData(scalaBuildTarget)

      target2.setDisplayName("target 2")
      target2.setBaseDirectory(targetId2.getUri)
      target2.setDataKind(BuildTargetDataKind.SCALA)
      target2.setData(scalaBuildTarget)

      target3.setDisplayName("target 3")
      target3.setBaseDirectory(targetId3.getUri)
      target3.setDataKind(BuildTargetDataKind.SCALA)
      target3.setData(scalaBuildTarget)

      val result = new WorkspaceBuildTargetsResult(compileTargets.values.toList.asJava)
      result
    })

  override def buildTargetSources(params: SourcesParams): CompletableFuture[SourcesResult] =
    handleRequest(() => {

      val sourceDir1 = new URI(targetId1.getUri).resolve("src/")
      val item1 = new SourceItem(asDirUri(sourceDir1), SourceItemKind.DIRECTORY, true)
      val items1 = new SourcesItem(targetId1, List(item1).asJava)

      val sourceDir2 = new URI(targetId2.getUri).resolve("src-gen/")
      val item2 = new SourceItem(asDirUri(sourceDir2), SourceItemKind.DIRECTORY, true)
      val items2 = new SourcesItem(targetId2, List(item2).asJava)

      val sourceDir3 = new URI(targetId3.getUri).resolve("sauce/")
      val sourceFile1 = new URI(targetId3.getUri).resolve("somewhere/sourcefile1")
      val sourceFile2 = new URI(targetId3.getUri).resolve("somewhere/below/sourcefile2")
      val sourceFile3 = new URI(targetId3.getUri).resolve("somewhere/sourcefile3")
      val item3Dir = new SourceItem(asDirUri(sourceDir3), SourceItemKind.DIRECTORY, false)
      val item31 = new SourceItem(sourceFile1.toString, SourceItemKind.FILE, false)
      val item32 = new SourceItem(sourceFile2.toString, SourceItemKind.FILE, false)
      val item33 = new SourceItem(sourceFile3.toString, SourceItemKind.FILE, true)
      val items3 = new SourcesItem(targetId3, List(item3Dir, item31, item32, item33).asJava)

      val result = new SourcesResult(List(items1, items2, items3).asJava)
      result
    })

  override def buildTargetInverseSources(
      params: InverseSourcesParams
  ): CompletableFuture[InverseSourcesResult] =
    handleRequest(() => {
      val result = new InverseSourcesResult(List(targetId1, targetId2, targetId3).asJava)
      result
    })

  override def buildTargetDependencySources(
      params: DependencySourcesParams
  ): CompletableFuture[DependencySourcesResult] =
    handleRequest(() => {

      val target1Sources = List("lib/Library.scala", "lib/Helper.scala", "lib/some-library.jar")
        .map(uriInTarget(targetId1, _).toString)
        .asJava
      val target2Sources =
        List("lib/LibraryTest.scala", "lib/HelperTest.scala", "lib/some-library.jar")
          .map(uriInTarget(targetId2, _).toString)
          .asJava
      val target3Sources = List("lib/App.scala", "lib/some-library.jar")
        .map(uriInTarget(targetId3, _).toString)
        .asJava

      val item1 = new DependencySourcesItem(targetId1, target1Sources)
      val item2 = new DependencySourcesItem(targetId2, target2Sources)
      val item3 = new DependencySourcesItem(targetId3, target3Sources)
      val result = new DependencySourcesResult(List(item1, item2, item3).asJava)

      result
    })

  override def buildTargetResources(params: ResourcesParams): CompletableFuture[ResourcesResult] =
    handleRequest(() => {
      // TODO provide resources
      val result = new ResourcesResult(List.empty.asJava)
      result
    })

  override def buildTargetCompile(params: CompileParams): CompletableFuture[CompileResult] =
    handleRequest(() => {
      params.getTargets.forEach(targetIdentifier => {
        compileTargets.get(targetIdentifier) match {
          case Some(target) =>
            if(!target.getCapabilities.getCanCompile)
              throw new RuntimeException(s"Target ${targetIdentifier.getUri} is not compilable")
          case None =>
        }
      })

      val origin = List(params.getOriginId).asJava
      val compile1Id = new TaskId("compile1id")
      compile1Id.setParents(origin)

      compileStart(compile1Id, "compile started: " + targetId1.getUri, targetId1)

      val subtaskParents = List(compile1Id.getId).asJava
      logMessage("spawning subtasks", task = Some(compile1Id), origin = Some(params.getOriginId))
      val subtask1Id = new TaskId("subtask1id")
      subtask1Id.setParents(subtaskParents)
      val subtask2Id = new TaskId("subtask2id")
      subtask2Id.setParents(subtaskParents)
      val subtask3Id = new TaskId("subtask3id")
      subtask3Id.setParents(subtaskParents)
      taskStart(subtask1Id, "resolving widgets", None, None)
      taskStart(subtask2Id, "memoizing datapoints", None, None)
      taskStart(subtask3Id, "unionizing beams", None, None)

      val compileme = new URI(targetId1.getUri).resolve("compileme.scala")
      val doc = new TextDocumentIdentifier(compileme.toString)

      val errorMessage = new Diagnostic(
        new Range(new Position(1, 10), new Position(1, 110)),
        "this is a compile error"
      )
      errorMessage.setSeverity(DiagnosticSeverity.ERROR)

      val warningMessage = new Diagnostic(
        new Range(new Position(2, 10), new Position(2, 20)),
        "this is a compile warning"
      )
      warningMessage.setSeverity(DiagnosticSeverity.WARNING)

      val infoMessage =
        new Diagnostic(new Range(new Position(3, 1), new Position(3, 33)), "this is a compile info")
      infoMessage.setSeverity(DiagnosticSeverity.INFORMATION)

      publishDiagnostics(
        doc,
        targetId1,
        List(errorMessage, warningMessage),
        Option(params.getOriginId)
      )
      publishDiagnostics(doc, targetId1, List(infoMessage), Option(params.getOriginId))

      taskFinish(subtask1Id, "targets resolved", StatusCode.OK, None, None)
      taskFinish(subtask2Id, "datapoints forgotten", StatusCode.ERROR, None, None)
      taskFinish(subtask3Id, "beams are classless", StatusCode.CANCELLED, None, None)
      showMessage("subtasks done", task = Some(compile1Id), origin = Option(params.getOriginId))

      compileReport(compile1Id, "compile failed", targetId1, StatusCode.ERROR)

      params.getTargets.asScala.foreach { target =>
        val compileId = new TaskId(UUID.randomUUID().toString)
        compileId.setParents(origin)
        compileStart(compileId, "compile started: " + target.getUri, target)
        taskProgress(compileId, "compiling some files", 100, 23, None, None)
        compileReport(compileId, "compile complete", target, StatusCode.OK)
      }

      val result = new CompileResult(StatusCode.OK)
      result.setOriginId(params.getOriginId)
      result
    })

  override def buildTargetTest(params: TestParams): CompletableFuture[TestResult] =
    handleRequest(() => {
      params.getTargets.forEach(targetIdentifier => {
        compileTargets.get(targetIdentifier) match {
          case Some(target) =>
            if(!target.getCapabilities.getCanTest)
              throw new RuntimeException(s"Target ${targetIdentifier.getUri} is not testable")
          case None =>
        }
      })
      // TODO some test task/report notifications
      // TODO some individual test notifications
      val result = new TestResult(StatusCode.OK)
      result.setOriginId(params.getOriginId)
      result
    })

  override def buildTargetRun(params: RunParams): CompletableFuture[RunResult] =
    handleRequest(() => {
      compileTargets.get(params.getTarget) match {
        case Some(target) =>
          if(!target.getCapabilities.getCanRun)
            throw new RuntimeException(s"Target ${target.getId.getUri} is not runnable")
        case None =>
      }
      // TODO some task notifications
      val result = new RunResult(StatusCode.OK)
      result.setOriginId(params.getOriginId)
      result
    })

  override def buildTargetCleanCache(
      params: CleanCacheParams
  ): CompletableFuture[CleanCacheResult] =
    handleRequest(() => {
      val result = new CleanCacheResult("cleaned cache", true)
      result
    })

  private def handleRequest[T](
      f: () => T,
      isBuildInitialize: Boolean = false,
      isBuildShutdown: Boolean = false
  ): CompletableFuture[T] = {
    val future = CompletableFuture.supplyAsync(f.asJava)
    if (isShutdown.isCompleted && !isBuildShutdown)
      future.completeExceptionally(
        new Exception("Cannot handle requests after receiving the shutdown request")
      )
    else if (!isInitialized.isCompleted && !isBuildInitialize) {
      Try(Await.result(isInitialized.future, 1.seconds)) match {
        case Failure(_) =>
          future.completeExceptionally(
            new Exception("Cannot handle requests before receiving the initialize request")
          )
        case Success(_) =>
      }
    }
    try {
      future.get()
    }catch {
      case exception: Exception => future.completeExceptionally(exception)
    }

    future
  }
}
