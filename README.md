# QuPath BIOP-OMERO extension

Welcome to the BIOP-OMERO extension for [QuPath](http://qupath.github.io)!

This adds support for accessing images hosted on an [OMERO](https://www.openmicroscopy.org/omero/) 
server through OMERO-ICE API.

The extension is intended for QuPath v0.5.x (at the time of writing).
It is not compatible with earlier QuPath versions. However, the 0.7.0 version of this extension is the last compatible one with QuPath 0.4.4.

## Documentation
You can find all the documentation on how to use this extension on our [wiki page](https://wiki-biop.epfl.ch/en/data-management/omero/qupath).

## Installing

*Downloads*

- To install the OMERO extension, download the latest `qupath-extension-biop-omero-[version].zip` file from [releases](https://github.com/BIOP/qupath-extension-biop-omero/releases/latest), unzip it and drag the two .jars onto the main QuPath window.

- If you haven't installed any extensions before, you'll be prompted to select a QuPath user directory.
The extension will then be copied to a location inside that directory.

- The `OMERO-java dependencies` are required to make this extension working. Download the .zip file from the [OMERO download page](https://www.openmicroscopy.org/omero/downloads/), under "OMERO java". Unzip it and copy the ``libs`` folder in your extension directory.

> WARNING
> 
> Due to an unexpected bug in QuPath 0.5.0, the dependencies nested in sub-folder are not read anymore. Alternatively, and until this QuPath bug will be fixed, instead of the ``libs`` folder, you can add the ``OMERO-Fiji plugin Image J / Fiji - omero_ij-5.8.3-all.jar``. This fat jar replaces the ``libs`` folder. It can be dowloaded from here https://www.openmicroscopy.org/omero/downloads/.



*Update*
- You might then need to restart QuPath (but not your computer).


## Building

You can build the extension using OpenJDK 17 or later with

```bash
gradlew clean build
```

The output will be under `build/libs`.
You can drag the jar file on top of QuPath to install the extension.
