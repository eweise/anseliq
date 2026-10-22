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
   * Builds the image for DOCKER_PLATFORM (e.g. linux/amd64) when it's set, and otherwise for this
   * machine's own architecture. Releases are built on amd64 CI runners, so a Mac only needs it to
   * build an amd64 image by hand (with `docker buildx install` done once); left unset there, the
   * image matches the Mac, which is what Docker Desktop's Kubernetes runs.
   */
  lazy val platformSetting = Docker / dockerBuildCommand := {
    sys.env.get("DOCKER_PLATFORM") match {
      case Some(platform) =>
        dockerExecCommand.value ++ Seq("buildx", "build", s"--platform=$platform", "--load") ++ dockerBuildOptions.value :+ "."
      case None => dockerBuildCommand.value
    }
  }
}
