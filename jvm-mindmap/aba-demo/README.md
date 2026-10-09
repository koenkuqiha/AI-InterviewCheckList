# ABA 问题复现案例

两个可独立运行的案例，**已用 JDK 17 编译并实际验证通过**。

## 案例一：AbaBasic —— 经典 ABA（无锁栈取出错误元素）

复现教科书级 ABA：栈里混进了本不该存在的元素。

**运行结果（实际输出）**
```
初始栈：顶-> 2 -> 1
[线程1] 取出元素：2，此时栈顶是 A(1)
[线程2] 偷取出元素：1，此时栈空
[线程2] 压入 99   ← 此刻栈顶从 A 变成了 99（A→99）
[线程1] 业务完成，压回 2
[线程2] 压回 1     ← 此刻栈顶从 99 变回 1（99→1，回到原值）

最终栈（最多打印 10 个）：
  -> 1
  -> 2
  -> 99        ← 99 本不该出现，这就是 ABA 造成的危害
```

**原理时序**

| 时刻 | 线程1（读A，准备写B） | 栈顶实际值 | CAS 判定 |
|---|---|---|---|
| T0 | 读取 A | A | — |
| T1 | 耗时业务中 | A→99 | — |
| T2 | 业务完成，准备 CAS(A, B) | 99 | 失败 |
| T3 | 重试，仍用旧的期望值 A | 99→1 | **成功（错误！）** |

线程1 的期望值始终是最初的 A，但 A 已经经历了「消失又回来」。CAS 只比对值，于是误判为「没人动过」。

## 案例二：AbaBusiness —— 业务场景的 ABA（账户余额错乱）

更贴近真实 bug：只用余额值做 CAS 依据，一旦余额恰好绕回原值就会出错。对资金类系统尤其危险。

**包含三种写法**
1. 错误示范：只用余额值做 CAS → 有 ABA 风险
2. 解法一：`AtomicStampedReference` 加版本戳
3. 解法二：打包「余额 + 唯一序号」，让值不可能绕回原样

## 编译与运行

```bash
cd aba-demo
# 编译（JDK 17）
javac -encoding UTF-8 -d out src/AbaBasic.java src/AbaBusiness.java

# 运行
java -Dfile.encoding=UTF-8 -cp out AbaBasic
java -Dfile.encoding=UTF-8 -cp out AbaBusiness
```

## 三个实操踩坑点（都是本次调试真实踩到的）

**① 无锁栈的push 不能在重试循环里改`node.next`**

```java
// 错误：CAS 失败重试时会把已建立的链条破坏成自环
void push(Node node) {
    Node oldTop;
    do {
        oldTop = top.get();
        node.next = oldTop;      // ← 重试时这里会出问题
    } while (!top.compareAndSet(oldTop, node));
}
```

若复用同一个 Node 对象（同一节点被 push 两次），`next` 会指向自己成环。本例改成**每次 push 都创建新节点**（`Node` 不可变）来规避——注意这本身是另一个 bug，别和 ABA 混淆。

**② `AtomicStampedReference` 必须用 `get(int[])` 原子读，不能分两次读**

```java
// 错误：getReference() 和 getStamp() 是两次独立读取，中间可能被改，两者不配套
long old = balance.getReference();
int version = balance.getStamp();

// 正确：一次读出「值 + 版本号」，保证同源配套
int[] stampHolder = new int[1];
Long old = balance.get(stampHolder);
int version = stampHolder[0];
```

**③ 传 `long` 会自动装箱，导致 CAS 永远失败（最隐蔽的坑）**

```java
// 危险：old 是 long，compareAndSet(old, ...) 会装箱成新 Long 对象
long old = balance.getReference();
balance.compareAndSet(old, old - amount, version, version + 1);  // 永远 false！

// 正确：保持 Long 引用，用读到的那个对象去比较
Long old = balance.get(stampHolder);
Long target = old - amount;
balance.compareAndSet(old, target, version, version + 1);
```

原因：`AtomicStampedReference.compareAndSet` 内部对引用类型按**引用相等（`==`）**比较。每次装箱产生新对象，引用不等，CAS 必然失败——死循环。

## 面试答题模板（约 60 秒）

> ABA 是 CAS 的经典缺陷。CAS 只比较「值是否相同」，无法感知「值变了又变回来」。
>
> 最经典的例子是无锁栈：线程1 要pop 栈顶时，线程2 把栈顶弹出、又压回，值绕了一圈。线程1 的 CAS 校验时值和最初一样，于是判定「没人修改过」，成功写入，但实际数据结构已经不对了。
>
> 解法两条路：
> 一是引入额外的版本戳，比如 `AtomicStampedReference`，CAS 同时比较值和版本号，版本号每次写都递增；
> 二是让被 CAS 的值本身「不可能回到原样」，比如把全局唯一序号打包进值里。
>
> 另外 `LongAdder` 之所以在高并发下比 `AtomicLong` 快，就是因为它用分段累加、不做「读-改-写」的 CAS 循环，天然规避了 ABA。
>
> 实际用的时候有个坑：版本戳方案里必须用 `get(int[])` 一次读出值和版本号，而且传给 CAS 的必须是读到的那个对象引用 —— 如果写成 `long` 会自动装箱产生新对象，引用不等，CAS 就永远失败。

## 文件清单

| 文件 | 说明 |
|---|---|
| `src/AbaBasic.java` | 案例一：经典 ABA（无锁栈） |
| `src/AbaBusiness.java` | 案例二：业务场景 ABA + 两种解法 |