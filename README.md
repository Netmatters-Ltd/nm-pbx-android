# NMPBX Android App

This is the NMPBX Android app for making and receiving calls, and managing your presence status.

It is a modified version of the Linphone open source project to whom we are very grateful. More information about [Linphone can be found on their web site](https://www.linphone.org/technical-corner/linphone).

You are welcome to use and modify this application however you wish so long as you comply with GPLv3. See the attached LICENSE.txt for details. However if you're looking for a VoIP phone client to use outside of the NMPBX system you are likely to find the base Linphone project more practical. Although this version has some features the base Linphone currently doesn't, it is only tested to work within the NMPBX ecosystem.

## Building

The project should be recognised by tools such as Android Studio and allow building debug releases with no further configuration.

To build for release you should generate/use a signing key, and update `keystore.properties` to reference it.

Assuming you have Android development tools installed, you should be able to build with:
```cmd
.\gradlew assembleRelease
```

The output of that will be in `{this project}\app\build\outputs\apk\release`

## Building for Play Store

### Upload Keystore

You need to populate `keystore.properties` with our details, and place the upload keystore in `.\app\nmpbx-upload.jks`. To maintain security, these are intentionally not included in this repo.

The password may contain characters that need to be escaped to be stored correctly in `keystore.properties`. To assist in populating it you can run the following small Java that checks the password against `nmpbx-upload.jks` and if correct, writes it to `keystore.properties`

```cmd
java -cp build/tools VerifyKeystorePassword
```
(You may need to give the full path to `java` if it's not in your `PATH`)

### Building as App Bundle

```cmd
.\gradlew :app:bundleRelease
```

The output of that will be `{this project}\app\build\outputs\bundle\release\app-release.aab`

### Check Signature

Check it's signed before uploading to Google Play:

```cmd
jarsigner -verify -verbose app\build\outputs\bundle\release\app-release.aab
```

`jarsigner` may not be in your `PATH` but it should be in the `bin` directory of your JDK install.

## Version number

Set in `app/build.gradle.kts` around line 103, `versionCode` and `versionName`.

Every upload to Google Play must have a higher `versionCode` than any previously uploaded file. Even if the earlier versions were not released, every upload has to be an increment.
