// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// Loaded from the pinned Chipyard root after installing this repository.
lazy val jpegxlPlatform = (project in file("generators/jpegxl/integration"))
  .dependsOn(LocalProject("rocketchip"))
  .settings(scalaVersion := "2.13.12",
    libraryDependencies ++= Seq("org.chipsalliance" %% "chisel" % "6.5.0",
      "edu.berkeley.cs" %% "chiseltest" % "6.0.0" % Test,
      "org.scalatest" %% "scalatest" % "3.2.19" % Test),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % "6.5.0" cross CrossVersion.full),
    Test / parallelExecution := false)
lazy val jpegxlSoc = (project in file("generators/jpegxl/examples/chipyard"))
  .dependsOn(LocalProject("chipyard"), jpegxlPlatform)
  .settings(assembly / test := {},
    assembly / assemblyMergeStrategy :=
      (LocalProject("chipyard") / assembly / assemblyMergeStrategy).value)
  .settings(scalaVersion := "2.13.12",
    libraryDependencies += "org.chipsalliance" %% "chisel" % "6.5.0",
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % "6.5.0" cross CrossVersion.full))
