# Alaphant

Alaphant (pronounced "al-a-phant") is a [Charles](https://charlesproxy.com) modification with the goal of adding some helpful features.

*Note: please support the developer of Charles and buy a license!*
(This project is *not owned or operated by the Charles developer*. It is being developed by Charles users who like the product and want to add extra features to it.)

## Building

To build, you will first need to copy a few files over from your Charles installation.

Copy the following files to the `target` directory:

- `lib/charles.dll`
- `lib/charles.jar`
- `lib/charles_win32.dll`

Next, run `./gradlew remapJar` - this will generate a version of the Charles Jar with more human-readable names present.

## Running

WIP - still trying to figure out the logistics of doing this outside a development environment.

```shell
java -jar Alaphant-1.0-SNAPSHOT.jar -Dsun.java2d.d3d=false -Djava.net.preferIPv4Stack=false -Djava.net.preferIPv6Addresses=true
```

## Mapping

Charles is partially obfuscated, so mappings need to be written in order to make it not look like gibberish.
We're primarily using [Fabric](https://github.com/fabricmc)'s remapping toolchain (specifically, the tiny mapping format).

> Ideally, we should probably in the future migrate off our current, hacky solution, which is using intermediaries directly
(instead of going from official -> named, we should go official -> intermediary -> named so that we can benefit from tools
like [Enigma](https://github.com/fabricmc/enigma).)

Until that migration though, you can just use the decompiler built in to IntelliJ to read through the Jar, and if you
find something you want to name, copy its placeholder name, find it in `mappings/mappings.tiny`, and swap it out.
To rebuild the remapped Jar to see your changes, run the `remapJar` Gradle task.
