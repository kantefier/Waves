import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport.toPlatformDepsGroupID
import sbt._

object Dependencies {

  def akkaModule(module: String) = "com.typesafe.akka" %% s"akka-$module" % "2.5.16"

  def swaggerModule(module: String) = ("io.swagger.core.v3" % s"swagger-$module" % "2.0.5").exclude("com.google.guava", "guava")

  def akkaHttpModule(module: String) = "com.typesafe.akka" %% module % "10.1.4"

  def nettyModule(module: String) = "io.netty" % s"netty-$module" % "4.1.24.Final"

  def kamonModule(module: String, v: String) = "io.kamon" %% s"kamon-$module" % v

  val asyncHttpClient = "org.asynchttpclient" % "async-http-client" % "2.4.7"

  lazy val network = Seq("handler", "buffer", "codec").map(nettyModule) ++ Seq(
    "org.bitlet" % "weupnp" % "0.1.4",
    // Solves an issue with kamon-influxdb
    asyncHttpClient.exclude("io.netty", "netty-handler")
  )

  lazy val testKit = scalatest ++ Seq(
    akkaModule("testkit"),
    "org.scalacheck" %% "scalacheck"                  % "1.13.5",
    "org.mockito"    % "mockito-all"                  % "1.10.19",
    "org.scalamock"  %% "scalamock-scalatest-support" % "3.6.0",
    ("org.iq80.leveldb" % "leveldb" % "0.9").exclude("com.google.guava", "guava"),
    akkaHttpModule("akka-http-testkit")
  )

  lazy val itKit = scalatest ++ Seq(
    // Swagger is using Jersey 1.1, hence the shading (https://github.com/spotify/docker-client#a-note-on-shading)
    ("com.spotify" % "docker-client" % "8.11.3").classifier("shaded").exclude("com.google.guava", "guava"),
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-properties" % "2.9.6",
    asyncHttpClient.exclude("io.netty", "netty-handler")
  )

  lazy val serialization = Seq(
    "com.google.guava"  % "guava"      % "21.0",
    "com.typesafe.play" %% "play-json" % "2.6.10"
  )
  lazy val akka = Seq("actor", "slf4j").map(akkaModule)

  lazy val db = Seq(
    "org.ethereum" % "leveldbjni-all" % "1.18.3"
  )

  lazy val logging = Seq(
    "ch.qos.logback"       % "logback-classic"          % "1.2.3",
    "org.slf4j"            % "slf4j-api"                % "1.7.25",
    "org.slf4j"            % "jul-to-slf4j"             % "1.7.25",
    "net.logstash.logback" % "logstash-logback-encoder" % "4.11"
  )

  lazy val http = Seq("core", "annotations", "models", "jaxrs2").map(swaggerModule) ++ Seq(
    "io.swagger"                   %% "swagger-scala-module" % "1.0.4",
    "com.github.swagger-akka-http" %% "swagger-akka-http"    % "1.0.0",
    "com.fasterxml.jackson.core"   % "jackson-databind"      % "2.9.6",
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.9.6",
    akkaHttpModule("akka-http")
  )

  lazy val matcher = Seq(
    akkaModule("persistence"),
    akkaModule("persistence-tck") % "test",
    "org.ethereum"                % "leveldbjni-all" % "1.18.3"
  )

  lazy val metrics = Seq(
    kamonModule("core", "1.1.3"),
    kamonModule("system-metrics", "1.0.0").exclude("io.kamon", "kamon-core_2.12"),
    kamonModule("akka-2.5", "1.1.1").exclude("io.kamon", "kamon-core_2.12"),
    kamonModule("influxdb", "1.0.2"),
    "org.influxdb" % "influxdb-java" % "2.11"
  ).map(_.exclude("org.asynchttpclient", "async-http-client"))

  lazy val fp = Seq(
    "org.typelevel"       %% "cats-core"       % "1.1.0",
    "org.typelevel"       %% "cats-mtl-core"   % "0.3.0",
    "io.github.amrhassan" %% "scalacheck-cats" % "0.4.0" % Test
  )
  lazy val meta = Seq("com.chuusai" %% "shapeless" % "2.3.3")
  lazy val monix = Def.setting(
    Seq(
      // exclusion and explicit dependency can likely be removed when monix 3 is released
      "io.monix" %%% "monix" % "3.0.0-RC2"
    ))
  lazy val scodec    = Def.setting(Seq("org.scodec" %%% "scodec-core" % "1.10.3"))
  lazy val fastparse = Def.setting(Seq("com.lihaoyi" %%% "fastparse" % "1.0.0", "org.bykn" %%% "fastparse-cats-core" % "0.1.0"))
  lazy val ficus     = Seq("com.iheart" %% "ficus" % "1.4.2")
  lazy val scorex = Seq(
    "org.scorexfoundation" %% "scrypto" % "2.0.4" excludeAll (
      ExclusionRule("org.slf4j", "slf4j-api"),
      ExclusionRule("com.google.guava", "guava")
    ))
  lazy val commons_net = Seq("commons-net"   % "commons-net" % "3.+")
  lazy val scalatest   = Seq("org.scalatest" %% "scalatest"  % "3.0.5")
  lazy val scalactic   = Seq("org.scalactic" %% "scalactic"  % "3.0.5")
  lazy val cats        = Seq("org.typelevel" %% "cats-core"  % "1.1.0")
  lazy val scalacheck = Seq(
    "org.scalacheck"      %% "scalacheck"      % "1.14.0",
    "io.github.amrhassan" %% "scalacheck-cats" % "0.4.0" % Test
  )
  lazy val kindProjector = "org.spire-math" %% "kind-projector" % "0.9.6"

  /**
    * Hackaton shit
    */
  lazy val doobieStuff = Seq(

    // Start with this one
    "org.tpolecat" %% "doobie-core"      % "0.6.0",
    "org.tpolecat" %% "doobie-hikari"    % "0.6.0",
    "org.tpolecat" %% "doobie-postgres"  % "0.6.0"

    // And add any of these as needed
//    "org.tpolecat" %% "doobie-h2"        % "0.6.0",          // H2 driver 1.4.197 + type mappings.
//    "org.tpolecat" %% "doobie-hikari"    % "0.6.0",          // HikariCP transactor.
//    "org.tpolecat" %% "doobie-postgres"  % "0.6.0",          // Postgres driver 42.2.5 + type mappings.
//    "org.tpolecat" %% "doobie-specs2"    % "0.6.0" % "test", // Specs2 support for typechecking statements.
//    "org.tpolecat" %% "doobie-scalatest" % "0.6.0" % "test"  // ScalaTest support for typechecking statements.
  )
}
