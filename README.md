# AndroidPerf

该部分实现了AndroidPerf的client端，其通过ADB端口映射以及socket与Android内的server连接，从而实现低开销的数据获取（在百元机上开销不到1%）。

## 源码结构

项目目前采用了JavaFX作为基本的UI框架，数据与界面分离，其主体包含两个Java包。
### `com.android.androidperf`包
这部分实现了主体的设备信息获取以及FPS、CPU、网络测量，还有UI控制逻辑。

* `AppController.java`
    
    这部分为JavaFX的controller模块，用以相应数据变化、界面更新以及基本控件的逻辑。
* `Device.java`
    
    包装了对某个特定连接设备的操作，包括执行ADB命令、获取设备基本信息、获取设备安装的包、获取当前界面的layer信息等操作。
* `BasePerfService.java`
    
    包括了所有监控服务的基本功能，包括维护性能数据的数据队列和启动数据更新线程以及数据绘制线程。
* `FPSPerfService.java`

    继承`BasePerfService.java`，实现特定的获取FPS数据的算法。会在`Device`初始化的时候被注册，并在device的指示下启动。

### `se.vidstige.jadb`包

这部分实现了不依赖ADB.exe来执行ADB命令的功能，方法是基于ADB协议构造ADB包，并发送给PC上的ADB Server。

## 编译

用Intellij IDEA导入项目并运行`Main.java`的main函数即可。项目依赖使用Maven管理，需要先在pom.xml中刷新一下Maven依赖（IDEA侧栏提供了相关功能）。

## 下载
下载链接为：https://cloud.tsinghua.edu.cn/f/1667d37b904348c48e63/

## 使用

1. 保证系统启动了ADB server。可以输入adb start-server命令来启动。
2. 双击AndroidPerf.exe使用，目前提供的版本为Windows版，不过程序本身是跨平台的，只是仅打包了Windows的二进制，需要的话可以再打包macOS或者Linux的版本。
3. 如下图所示，首先选择你的设备。
    <img src="doc/device.png">
4. 其次选择要测量的应用的包名。下拉列表中最顶上的包名对应的是当前正在运行的应用以方便选择。
    <img src="doc/package.png">
5. 点击下方的Start开始测量。
6. 如果有新的设备接入或者新的应用安装，点击Update按钮来更新两个列表。

## 测试
1. 目前测试的应用主要有B站、腾讯视频、优酷视频、Chrome浏览器、YouTube等视频应用，狂野飙车、原神等游戏应用，以及若干普通应用。
2. 测试所用的机型包括Pixel XL、Pixel 3 XL、Pixel 3a、Samsung Galaxy S20 Ultra、荣耀和小米的若干机型。
3. 测试的Android版本包括7.1、8、9、10、11、12
4. 测试的视频应用的特殊场景包括：视频切换、全屏切换、弹幕开关、悬浮窗播放、分屏播放、应用切换、直播。
