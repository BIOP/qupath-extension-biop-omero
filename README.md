# REPOSITORY STATUS - DEPRECATION NOTICE
> [!WARNING]
> The OMERO-RAW extension is now DEPRECATED and NOT SUPPORTED ANYMORE on QuPath 0.6.x. The latest version of QuPath compatible with this extension is **0.5.1**. We strongly recommend to use the [official extension](https://github.com/qupath/qupath-extension-omero).
> In order to transfer your OMERO-QuPath projects to be compatible with the new extension, please visit [our wiki page](https://wiki-biop.epfl.ch/en/data-management/omero/qupath#migration-from-qupath-05x-to-qupath-06x) to run a migration script
> in QuPath and follow the step-by-step instructions written at the beginning of the script.

> [!WARNING]
> For QuPath 0.5.x users, the **qupath-extension-biop-omero** will not be developed further, except to fix major bugs on QuPath 0.5.x
> Please see below for the installation / documentation


# QuPath BIOP-OMERO extension

Welcome to the BIOP-OMERO extension for [QuPath](http://qupath.github.io)!

This adds support for accessing images hosted on an [OMERO](https://www.openmicroscopy.org/omero/) 
server through [simple-omero-client](https://github.com/GReD-Clermont/simple-omero-client) API, based on OMERO-ICE API.

The extension is intended for QuPath v0.5.x (at the time of writing).
It is NOT compatible with later QuPath versions. 

## Installing
Please follow the [installation instructions](https://wiki-biop.epfl.ch/en/data-management/omero/qupath#on-qupath-05x) explained on our wiki

## Documentation
- You can find all the documentation on how to use this extension on our [wiki page](https://wiki-biop.epfl.ch/en/data-management/omero/qupath#on-qupath-05x).
- The JavaDoc of this project is available on the [GitHub Page](https://biop.github.io/qupath-extension-biop-omero/qupath/ext/biop/servers/omero/raw/package-summary.html).

### Template scripts
- All QuPath-OMERO commands can be used in QuPath scripts (groovy language).
Template scripts that make the use of the scripting API are available on our [GitHub - qupath-scripts](https://github.com/BIOP/qupath-scripts/tree/qp0.5.x/Extensions/OMERO).

## Building

You can build the extension using OpenJDK 17 or later with

```bash
gradlew clean build
```

The output will be under `build/libs`.
You can drag the jar file on top of QuPath to install the extension.
