# Recording options

## In-client recording

This is recording entirely on the local device. It is separate from call recording that happens on the server.

Defaults to not being available. Add the following into a provisioning XML file to enable it.

```xml
<section name="ui">
  <entry name="show_call_recording_button" overwrite="true">1</entry>
</section>
```