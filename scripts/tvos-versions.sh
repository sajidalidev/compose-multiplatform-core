#!/bin/bash

## Single source of truth for the tvOS fork's PUBLISH-TIME VERSION STAMPS.
##
## VERSION_<LIB> below is the version identity each library group is published under via
## -Pjetbrains.publication.version.<LIB>, i.e. the coordinate a consumer actually requests.
## LIBRARIES is the set of library groups published together; every one of them must always
## be passed a version property, otherwise internal POM edges fall back to 9999.0.0-SNAPSHOT.
##
## The rationale for each individual pin (why COMPOSE is JetBrains' 1.12.0 rather than the
## in-tree androidx number, etc.) lives in the header of publish-tvos-fork.sh.
##
## This file is meant to be SOURCED, not executed. It is sourced by:
##   scripts/publish-tvos-fork.sh
##   scripts/publish-tvos-fork-reposilite.sh
## Update the pins here and both flows stay in sync.

VERSION_COMPOSE="1.12.0"
VERSION_COMPOSE_MATERIAL3="1.12.0-alpha03"
VERSION_COMPOSE_MATERIAL3_ADAPTIVE="1.3.0-beta02"
VERSION_NAVIGATION="2.10.0-alpha02"
VERSION_NAVIGATION_3="1.2.0-alpha04"
VERSION_WINDOW="1.6.0-alpha02"
VERSION_TV_MATERIAL="1.1.0-alpha01"

LIBRARIES="COMPOSE,COMPOSE_MATERIAL3,COMPOSE_MATERIAL3_ADAPTIVE,NAVIGATION,NAVIGATION_3,WINDOW,TV_MATERIAL"
