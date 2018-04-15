import coursier._
import java.io.File
import java.net.{URL, URLClassLoader}
import java.nio.file.{Files, Paths}
import java.util.Optional
import sbt.internal.inc.javac.JavaCompiler
import sbt.internal.inc.{FileAnalysisStore => _, _}
import sbt.internal.util.{ConsoleOut, MainAppender}
import sbt.util.{Level, LogExchange}
import scala.collection.JavaConverters._
import scalaz.concurrent.Task
import xsbti.compile.{ScalaInstance => _, _}

private class ChildFirstLoader(urls: Array[URL], parent: ClassLoader) extends URLClassLoader(urls, null) {
  protected override def findClass(name: String): Class[_] =
    try super.findClass(name) catch { case _: ClassNotFoundException => parent.loadClass(name) }
}

object Main {
  private[this] val zincVersion = "1.1.3"

  private[this] val logger = LogExchange.logger("compile")
  LogExchange.bindLoggerAppenders(logger.name, List(MainAppender.defaultScreen(ConsoleOut.systemOut) -> Level.Info))

  def main(args: Array[String]): Unit = {
    val scalaVersion = args match {
      case Array(scalaVersion) => scalaVersion
      case _ =>
        logger.info(s"No Scala version specified; defaulting to ${Properties.versionNumberString}")
        Properties.versionNumberString
    }
    // real implementation in librarymanagement-core
    val scalaBinaryVersion =
      if (scalaVersion.matches(raw"\d+.\d+.\d+")) scalaVersion.split('.').dropRight(1).mkString(".")
      else scalaVersion

    val bridgeModule = Module("org.scala-sbt", s"compiler-bridge_$scalaBinaryVersion")
    val compilerModule = Module("org.scala-lang", "scala-compiler")
    val libraryModule = Module("org.scala-lang", "scala-library")
    val interfaceModule = Module("org.scala-sbt", "compiler-interface")

    // fetch dependencies
    val resolution = Resolution(
      Set(Dependency(bridgeModule, zincVersion), Dependency(compilerModule, scalaVersion)),
      filter = Some(_.module != interfaceModule) // rather, share this with current classloader
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

    // configure scalac
    val compilerJars = fetched.values.toArray
    val scalaInstance = new ScalaInstance(
      scalaVersion,
      new ChildFirstLoader(compilerJars.map(_.toURI.toURL), getClass.getClassLoader),
      null,
      fetched(libraryModule),
      fetched(compilerModule),
      compilerJars,
      None
    )

    // misc settings
    val compilerCache = new FreshCompilerCache()
    val reporter = new ManagedLoggedReporter(20, logger)

    // set inputs and outputs
    val inputClasspath = Array(fetched(libraryModule))
    val inputSources = Files.walk(Paths.get("example/src")).iterator.asScala
      .collect { case file if file.toString.endsWith(".scala") => file.toFile }
      .toArray
    val output = CompileOutput(new File("example/target"))

    // handle persistence
    val store = FileAnalysisStore.getDefault(new File("example/zinc"))
    val lookup = new PerClasspathEntryLookup {
      // load analysis files from other compilations
      def analysis(classpathEntry: File) = Optional.empty[CompileAnalysis]
      def definesClass(classpathEntry: File) = Locate.definesClass(classpathEntry)
    }

    // compile
    val previous = store.get()
    val result = ZincUtil.defaultIncrementalCompiler.compile(
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
