#!/bin/bash

GRADLE_LOCATION=./app/build.gradle
export JAVA_HOME=/usr/lib/jvm/java-26-openjdk

APP_NAME="FFShare"
APP_VERSION=$(grep -Po '(?<=^def releaseVersionName = ").*(?=")' "$GRADLE_LOCATION")
APP_VERSION_CODE=$(grep -Po '(?<=versionCode ).*' "$GRADLE_LOCATION")

# Everything below builds paths out of these. An empty APP_VERSION once turned the
# cleanup step into `rm -rf ./github_releases//*`, wiping every previous release.
if [ -z "$APP_VERSION" ] || [ -z "$APP_VERSION_CODE" ]; then
    echo "error: could not read releaseVersionName/versionCode from $GRADLE_LOCATION" >&2
    exit 1
fi

# no pre-release version argument
if [ -z "$1" ]; then
    ./gradlew assembleRelease
else
    PRE_VERSION_NAME="$1"
    # create backup of gradle to revert back to after bumping version and assembling release
    cp "$GRADLE_LOCATION" "$GRADLE_LOCATION.original"

    # change app version to pre version and bumb version code
    APP_VERSION="$PRE_VERSION_NAME"
    APP_VERSION_CODE="$((APP_VERSION_CODE + 1))"

    sed -i -e "s/^def releaseVersionName = \".*\"/def releaseVersionName = \"${APP_VERSION}\"/" "$GRADLE_LOCATION"
    sed -i -e "s/versionCode .*/versionCode ${APP_VERSION_CODE}/g" "$GRADLE_LOCATION"

    ./gradlew assembleRelease

    # revert to original gradle version after build finished
    mv "$GRADLE_LOCATION.original" "$GRADLE_LOCATION"
fi


OUTPUT_FOLDER="./github_releases/$APP_VERSION"


mkdir -p "$OUTPUT_FOLDER" 2>/dev/null
rm -rf "$OUTPUT_FOLDER"/* # clean if rebuild

cp ./app/build/outputs/apk/release/app-universal-release.apk "$OUTPUT_FOLDER/${APP_NAME}_${APP_VERSION}.apk"

changelog=$(cat "./fastlane/metadata/android/en-US/changelogs/$APP_VERSION_CODE.txt")

# title
echo "$APP_NAME $APP_VERSION" > "$OUTPUT_FOLDER/release"

# changelog
echo "=== Changelog ===" >> "$OUTPUT_FOLDER/release"
echo "$changelog" >> "$OUTPUT_FOLDER/release"

# sha256, in the format UpdateChecker.parseChecksum reads, so a manually built
# release can be verified by the app exactly as a CI one is
(cd "$OUTPUT_FOLDER" && sha256sum *.apk > SHA256SUMS)

# the same sums, rendered for humans in the release notes
echo "=== SHA256 ===" >> "$OUTPUT_FOLDER/release"
while read -r sha base; do
    base=$(basename "$base")
    size=$(du -hk "$OUTPUT_FOLDER/$base" | awk '{ printf "%.1fM", $1/1024 }')
    echo "$sha  $base ($size)" >> "$OUTPUT_FOLDER/release"
done < "$OUTPUT_FOLDER/SHA256SUMS"

