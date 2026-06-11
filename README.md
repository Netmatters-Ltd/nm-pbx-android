# NMPBX Android App

This is the NMPBX Android app for making and receiving calls, and managing your presence status.

It is a modified version of the Linphone open source project to whom we are very grateful. More information about [Linphone can be found on their web site](https://www.linphone.org/technical-corner/linphone).

You are welcome to use and modify this application however you wish so long as you comply with GPLv3. See the attached LICENSE.txt for details. However if you're looking for a VoIP phone client to use outside of the NMPBX system you are likely to find the base Linphone project more practical. Although this version has some features the base Linphone currently doesn't, it is only tested to work within the NMPBX ecosystem.

## Building

The project should be recognised by tools such as Android Studio and allow building debug releases with no further configuration.

To build for release you should generate/use a signing key, and update `keystore.properties` to reference it.

Assuming you have Android development tools installed, you should be able to build with:
```pwsh
.\gradlew assembleRelease
```
