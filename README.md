# AndroidPerf

AndroidPerf is a tool for profiling your Android devices.
It supports accurate and efficient measurement of Android apps' running smoothness (FPS), CPU usages and network traffic.

Our tests show that AndroidPerf provides more accurate FPS measurement on video streaming apps as compared to Solopi and PerfDog.
For example, Solopi cannot accurately measure almost all streaming apps, while PerfDog cannot handle overlay-based video playing.

## Platforms

AndroidPerf runs on Windows/Linux/macOS computers to which your phones are connected.
It currently supports Android 7.0+.
Testings have been conducted on various vendors' devices, including Google, Samsung, Xiaomi, Huawei, etc.

## Usages

1. Ensure that ADB server has been started on your computer. You can do this through `adb start-server`.
2. Connect your device to the computer. Make sure that you have turned on `USB debugging` in the developer options. 
3. Execute the main program of AndroidPerf.
4. Select your device. 
   <img src="doc/device.png">
5. Now select the package name for testing. The top most package name is that of the currently active app.
   <img src="doc/package.png">
6. Click `Start` to begin profiling. 
7. If you have connected new devices or installed new apps, click `Update` to refresh the list.

## Attributions

We thank vidstige's [jadb](https://github.com/vidstige/jadb) project which enables adb communications without Google's `adb` binary.

## Compile

Use Intellij IDEA to import the project and run `Main.java`.
We use Maven for package management.

## Contributing

AndroidPerf is far from optimal. We thank everyone willing to contribute to the project.
Please file an issue or create a pull request to contribute.
We expect all bug reports to contain full environment info, including that of host OSes and test phones.

## Licence

This project is released under the Apache License Version 2.0.