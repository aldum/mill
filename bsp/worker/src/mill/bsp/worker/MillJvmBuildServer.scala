package mill.bsp.worker

import ch.epfl.scala.bsp4j.{
  BuildTargetIdentifier,
  JvmBuildServer,
  JvmEnvironmentItem,
  JvmMainClass,
  JvmRunEnvironmentParams,
  JvmRunEnvironmentResult,
  JvmTestEnvironmentParams,
  JvmTestEnvironmentResult
}
import mill.T
import mill.bsp.spi.MillBuildServerBase
import mill.scalalib.JavaModule
import mill.scalalib.bsp.BspUri

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters._

class MillJvmBuildServer(base: MillBuildServerBase)
    extends MillBspExtension
    with JvmBuildServer {

  override def extensionCapabilities: ExtensionCapabilities = ExtensionCapabilities(
    languages = Seq()
  )

  override def buildTargetJvmRunEnvironment(params: JvmRunEnvironmentParams)
      : CompletableFuture[JvmRunEnvironmentResult] = {
    jvmRunTestEnvironment(
      s"jvmRunEnvironment ${params}",
      params.getTargets.asScala.toSeq,
      new JvmRunEnvironmentResult(_)
    )
  }

  override def buildTargetJvmTestEnvironment(params: JvmTestEnvironmentParams)
      : CompletableFuture[JvmTestEnvironmentResult] = {
    jvmRunTestEnvironment(
      s"jvmTestEnvironment ${params}",
      params.getTargets.asScala.toSeq,
      new JvmTestEnvironmentResult(_)
    )
  }

  def jvmRunTestEnvironment[V](
      name: String,
      targetIds: Seq[BuildTargetIdentifier],
      agg: java.util.List[JvmEnvironmentItem] => V
  ): CompletableFuture[V] = {
    base.completableTasks(
      hint = name,
      targetIds = _ => targetIds.map(_.bspUri),
      tasks = {
        case m: JavaModule =>
          T.task {
            (
              m.runClasspath(),
              m.forkArgs(),
              m.forkWorkingDir(),
              m.forkEnv(),
              m.mainClass(),
              m.zincWorker().worker(),
              m.compile()
            )
          }
      }
    ) {
      // We ignore all non-JavaModule
      case (
            ev,
            state,
            id,
            m: JavaModule,
            (runClasspath, forkArgs, forkWorkingDir, forkEnv, mainClass, zincWorker, compile)
          ) =>
        val classpath = runClasspath.map(_.path).map(BspUri.sanitizeUri)
        val item = new JvmEnvironmentItem(
          id.buildTargetIdentifier,
          classpath.iterator.toSeq.asJava,
          forkArgs.asJava,
          forkWorkingDir.toString(),
          forkEnv.asJava
        )

        val classes = mainClass.toList ++ zincWorker.discoverMainClasses(compile)
        item.setMainClasses(classes.map(new JvmMainClass(_, Nil.asJava)).asJava)
        item
    } {
      agg
    }
  }
}
