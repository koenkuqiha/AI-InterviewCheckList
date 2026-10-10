@echo off
REM OOM 五大场景一键复现脚本（Windows CMD）
REM 用法：双击运行，或在 IDEA Terminal 里执行 run-all.bat
setlocal

where java >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 java，请先配置 JAVA_HOME 或 PATH
    exit /b 1
)

echo === 编译所有 demo ===
if not exist out mkdir out
javac -encoding UTF-8 -d out src\*.java
if errorlevel 1 (
    echo [错误] 编译失败
    exit /b 1
)
echo 编译成功
echo.

echo === [1/5] Java heap space ^(参数 -Xmx64m^) ===
java -Xmx64m -cp out HeapOOMDemo
echo.

echo === [2/5] Metaspace ^(参数 -XX:MaxMetaspaceSize=32m^) ===
java -XX:MaxMetaspaceSize=32m -cp out Demo2_Metaspace
echo.

echo === [4/5] Direct buffer memory ^(参数 -XX:MaxDirectMemorySize=32m^) ===
java -XX:MaxDirectMemorySize=32m -cp out Demo4_DirectBufferMemory
echo.

echo === [5/5] Requested array size exceeds VM limit ^(无参数^) ===
java -cp out Demo5_ArraySizeLimit
echo.

echo === [3/5] unable to create new native thread ^(参数 -Xmx64m -Xss256k^) ===
echo 提示：此场景需创建 9 万+线程，耗时 2-4 分钟，按 Ctrl+C 可跳过
java -Xmx64m -Xss256k -cp out Demo3_NativeThread

endlocal