package com.demo;

/**
 * 待被自定义类加载器加载的示例类。
 * 编译产物放在 lib/ 目录，且不进入运行时的 classpath，
 * 这样系统类加载器（AppClassLoader）找不到它，必然走到自定义 ClassLoader 的 findClass。
 */
public class SampleService {

    static {
        // 类被加载时打印一次，用于观察加载时机
        System.out.println("[SampleService] 静态代码块执行，由 " + SampleService.class.getClassLoader() + " 触发加载");
    }

    public void hello() {
        System.out.println("  SampleService.hello() 被调用，this 的加载器 = " + this.getClass().getClassLoader());
    }

    public String whoLoadedMe() {
        return this.getClass().getClassLoader().toString();
    }
}
