set export

clean:
    sbt clean

bsp-install:
    sbt bloopInstall

bloop_version := "1.5.18"
connection_file_generate := """
cat <<EOF > .bsp/bloop.json
{
    "name": "Bloop",
    "version": "1.5",
    "bspVersion": "2.0.0",
    "languages": [
        "scala"
    ],
    "argv": [
        "cs",
        "launch",
        "ch.epfl.scala:bloop-launcher-core_2.13:${bloop_version}",
        "-M",
        "bloop.launcher.Launcher",
        "--",
        "${bloop_version}"
    ]
}
EOF
"""

bsp-connection-file:
    mkdir .bsp || true
    `{{connection_file_generate}}`

setup-ide: bsp-connection-file bsp-install

