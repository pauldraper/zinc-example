import coursier._
import java.io.File
import java.net.{URL, URLClassLoader}
import java.nio.file.{Files, Paths}
import java.util.Optional
import sbt.internal.inc._
import sbt.internal.inc.javac.JavaCompiler
import sbt.internal.util.{ConsoleOut, MainAppender}
import sbt.util.{Level, LogExchange}
import scalaz.concurrent.Task
import xsbti.compile.{ScalaInstance => _, _}

class ChildFirstLoader(urls: Array[URL], parent: ClassLoader) extends URLClassLoader(urls, null) {
  protected override def findClass(name: String): Class[_] =
    try super.findClass(name) catch { case _: ClassNotFoundException => parent.loadClass(name) }
}

object Main {
  private[this] val scalaBinaryVersion = "2.11"
  private[this] val scalaVersion = "2.11.12"
  private[this] val zincVersion = "1.1.3"

  private[this] val logger = LogExchange.logger("compile")
  LogExchange.bindLoggerAppenders(logger.name, List(MainAppender.defaultScreen(ConsoleOut.systemOut) -> Level.Info))

  def main(args: Array[String]): Unit = {
    val bridgeModule = Module("org.scala-sbt", s"compiler-bridge_$scalaBinaryVersion")
    val compilerModule = Module("org.scala-lang", "scala-compiler")
    val libraryModule = Module("org.scala-lang", "scala-library")
    val interfaceModule = Module("org.scala-sbt", "compiler-interface")

    val resolution = Resolution(
      Set(Dependency(bridgeModule, zincVersion), Dependency(compilerModule, scalaVersion)),
      filter = Some(_.module != interfaceModule)
    )
    val fetched = resolution.process
      .run(Fetch.from(Seq(Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2")), Cache.fetch()))
      .flatMap { resolution =>
        Task.gatherUnordered(resolution.dependencyArtifacts.collect {
          case (dependency, artifact) if artifact.`type` == "jar" =>
            Cache.file(artifact).run
              .map(dependency.module -> _.getOrElse(sys.error(s"Download failed for ${dependency.module.toString}")))
        })
      }
      .unsafePerformSync.toMap

    val compilerJars = fetched.values.toArray
    val scalaInstance = new ScalaInstance(
      "2.11.12",
      new ChildFirstLoader(compilerJars.map(_.toURI.toURL), getClass.getClassLoader),
      null,
      fetched(libraryModule),
      fetched(compilerModule),
      compilerJars,
      None
    )

    val compilerCache = new FreshCompilerCache()
    val reporter = new ManagedLoggedReporter(20, logger)

    val inputClasspath = Array(fetched(libraryModule))
    val inputSources = Files.walk(Paths.get("example/src"))
      .filter(_.toString.endsWith(".scala")).map[File](_.toFile)
      .toArray(new Array[File](_))

    val output = CompileOutput(new File("example/target"))

    val store = xsbti.compile.FileAnalysisStore.getDefault(new File("example/zinc"))

    val incrementalCompile = ZincUtil.defaultIncrementalCompiler
    val lookup = new PerClasspathEntryLookup {
      // load analysis files from other compilations
      def analysis(classpathEntry: File) = Optional.empty[CompileAnalysis]
      def definesClass(classpathEntry: File) = Locate.definesClass(classpathEntry)
    }

    val previous = store.get()
    val result = incrementalCompile.compile(
      ZincUtil.scalaCompiler(scalaInstance, fetched(bridgeModule)),
      JavaCompiler.local.getOrElse(JavaCompiler.fork(None)),
      inputSources.map(_.getAbsoluteFile),
      inputClasspath,
      output,
      compilerCache,
      Array.empty,
      Array.empty,
      previous.map[CompileAnalysis](_.getAnalysis),
      previous.map[MiniSetup](_.getMiniSetup),
      lookup,
      reporter,
      CompileOrder.Mixed,
      false,
      Optional.empty(),
      IncOptions.of(),
      Array.empty,
      logger
    )
    store.set(AnalysisContents.create(result.analysis, result.setup))
  }
}
