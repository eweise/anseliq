import sbt.*
import com.typesafe.sbt.SbtNativePackager.Docker
import com.typesafe.sbt.packager.Keys.{dockerBuildCommand, dockerExecCommand, dockerBuildOptions}
import com.typesafe.sbt.packager.docker.DockerAlias

object DockerSettings {

  /**
   * Where the images are published, e.g. ghcr.io/eweise/anseliq-backend:1.2.3 and
   * ghcr.io/eweise/anseliq-frontend:1.2.3. DOCKER_REGISTRY overrides it, e.g. to push to a
   * customer's own registry.
   */
  lazy val repository = sys.env.getOrElse("DOCKER_REGISTRY", "ghcr.io/eweise")

  /**
   * An extra "dev" tag on a build that isn't a release, which the local overlay deploys, so it
   * doesn't need its tag changing for every commit. A release gets only its version tag.
   */
  def devAlias(alias: DockerAlias, isSnapshot: Boolean): Seq[DockerAlias] =
    if (isSnapshot) Seq(alias.withTag(Some("dev"))) else Seq.empty


  /**
   * * support build x86 - intel CPU image on Mac M1 chip required run 'docker buildx install' to
   * set docker buildx up
   */
  lazy val x86ArchSetting = Docker / dockerBuildCommand := {
    val armArch = List("aarch64", "arm64") // Mac ARM chip M
    if (armArch.contains(sys.props("os.arch"))) {
      dockerExecCommand.value ++ Seq("buildx", "build", "--platform=linux/amd64", "--load") ++ dockerBuildOptions.value :+ "."
    } else dockerBuildCommand.value
  }
}
