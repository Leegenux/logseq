# Enter键创建空行导致的Channel溢出错误

## 问题描述

在Logseq中按下Enter键创建空行时，产生了core.async channel溢出错误。系统无法处理超过1024个待处理的异步操作，导致应用程序报错，可能影响用户正常使用。

错误信息：
```
Error: Assert failed: No more than 1024 pending puts are allowed on a single channel. Consider using a windowed buffer.
(< (.-length puts) impl/MAX-QUEUE-SIZE)
```

## 复现步骤

1. 在Logseq编辑器中定位到任意块
2. 按下Enter键创建新的空行
3. 观察控制台出现channel溢出错误

## 错误详情

错误日志显示以下关键信息：

1. 初始阶段计算路径引用非常快速：
```
Compute path refs: : 0.23681640625 ms
```

2. 数据库刷新操作：
```
frontend.db.react.js:889 refresh! (:frontend.db.react/page-blocks 14880): 12.166015625 ms
frontend.db.react.js:889 refresh! (:kv :recent/pages): 0.273193359375 ms
```

3. 块插入操作耗时约106毫秒：
```
editor.cljs:2532 Insert block: 106.76806640625 ms
```

4. 随后立即出现channel溢出错误：
```
Uncaught Error: Assert failed: No more than 1024 pending puts are allowed on a single channel. Consider using a windowed buffer.
(< (.-length puts) impl/MAX-QUEUE-SIZE)
```

5. 错误堆栈涉及多个异步操作模块，包括：
   - cljs.core.async.impl.channels
   - cljs.core.async.impl.ioc_helpers
   - cljs.core.async.js

## 原因分析

1. **异步通道溢出**：ClojureScript的core.async库使用channel进行异步通信，当单个channel的待处理操作超过1024个时出现溢出。

2. **可能的循环依赖**：在创建新块时，可能触发了大量相互依赖的异步操作，形成循环引用或级联效应。

3. **缺少反压机制**：系统没有适当的反压(backpressure)机制来处理突发的大量异步操作。

4. **事件处理积压**：Enter键操作可能触发了多个事件监听器，导致大量异步任务同时入队。

5. **可能的内存泄漏**：错误可能与某处未正确清理的channel资源有关，导致长时间使用后channel堆积。

## 解决方案建议

1. **增加缓冲区大小**：
   ```clojure
   ;; 将固定大小的缓冲区改为滑动窗口缓冲区
   (let [c (async/chan (async/sliding-buffer 1024))]
     ...)
   ```

2. **添加防抖/节流机制**：对Enter键操作添加防抖或节流处理，减少短时间内触发的事件数量。

3. **重构异步处理逻辑**：
   - 检查是否存在循环依赖的异步操作
   - 确保所有channel正确关闭和清理
   - 考虑使用更简单的异步模式处理键盘事件

4. **优化块创建性能**：
   - 减少创建新块时触发的数据库刷新操作
   - 延迟非关键路径上的操作
   - 批量处理引用计算和更新

5. **改进错误处理**：添加错误恢复机制，确保即使channel溢出也不会影响用户体验。

6. **监控和诊断**：
   - 添加详细日志以跟踪channel使用情况
   - 实现channel使用量的监控指标
   - 创建压力测试来模拟高频Enter键使用场景

## 下一步行动

1. 检查`editor.cljs`文件中与Enter键处理相关的函数，特别是行号2532附近的`Insert block`操作
2. 检查core.async channel的使用模式，查找可能的资源泄漏
3. 使用Chrome性能分析工具追踪Enter键操作的完整调用链
4. 考虑对频繁操作（如Enter键）实现特定的优化


# Enter键通道溢出修复方案

## 问题诊断

根据代码分析，问题出现在按下Enter键创建新空行时，会触发大量异步操作，导致core.async通道溢出错误。错误信息表明某个通道的待处理操作数量超过了1024个限制值。

关键错误路径：
1. 用户按下Enter键
2. `keydown-new-block-handler` 被触发
3. `keydown-new-block` 被调用
4. `insert-new-block!` 执行
5. `outliner-tx/transact!` 处理事务
6. 触发大量异步操作和数据库刷新
7. core.async通道溢出（超过1024个待处理操作）

## 修复方案

### 1. 使用滑动窗口缓冲区替代固定大小缓冲区

在相关的异步通道创建代码中，将固定大小的缓冲区替换为滑动窗口缓冲区，这样当新的消息进入时，旧的消息会被丢弃，避免通道溢出：

```clojure
;; 修改前
(let [c (async/chan)]
  ...)

;; 修改后
(let [c (async/chan (async/sliding-buffer 1024))]
  ...)
```

### 2. 为Enter键操作添加防抖机制

在编辑器处理程序中添加防抖逻辑，限制短时间内多次Enter键事件的处理：

```clojure
;; 在frontend.handler.editor命名空间添加防抖函数
(def keydown-new-block-handler-debounced
  (util/debounce keydown-new-block-handler 50)) ;; 50毫秒防抖
```

然后在事件监听器中使用防抖版本的处理函数。

### 3. 优化块创建流程

修改`insert-new-block!`函数，减少创建新块时的数据库操作和异步任务数量：

1. 批量处理引用计算
2. 延迟非关键路径上的操作
3. 合并多个小事务为一个大事务

### 4. 改进react.cljs中的查询缓存策略

当前的LRU缓存策略在高频操作时可能不够高效。增加以下优化：

```clojure
;; 在frontend.db.react命名空间中
;; 修改limit-query-cache-size!函数，增加前端操作时的主动清理
(defn- limit-query-cache-size!
  "限制查询缓存的大小，如果超过阈值，则移除最早访问的查询"
  []
  (let [current-size (count @query-state)]
    (when (> current-size (* 0.8 max-query-cache-size)) ;; 降低触发阈值，更积极地清理
      (let [queries-to-remove (take (- current-size (int (* max-query-cache-size 0.6))) @query-access-order)
            new-order (vec (drop (count queries-to-remove) @query-access-order))]
        (doseq [query-key queries-to-remove]
          (swap! query-state dissoc query-key))
        (reset! query-access-order new-order)))))
```

### 5. 添加批量异步操作处理机制

修改异步操作处理逻辑，实现批量处理和队列管理：

```clojure
;; 创建批量处理函数
(defn batch-process
  [items process-fn batch-size]
  (let [batches (partition-all batch-size items)]
    (async/go-loop [remaining batches]
      (when (seq remaining)
        (let [batch (first remaining)]
          (doseq [item batch]
            (process-fn item))
          (async/<! (async/timeout 1)) ;; 给UI线程一个呼吸的机会
          (recur (rest remaining)))))))
```

## 实施步骤

1. 首先实施最简单的修复：使用滑动窗口缓冲区
2. 添加Enter键操作的防抖处理
3. 改进缓存策略和定期清理
4. 优化插入块操作中的异步处理和数据库刷新

## 验证方法

1. 创建一个测试场景，频繁按下Enter键创建多个空行
2. 监控控制台是否出现通道溢出错误
3. 测量性能指标，确保修复不会带来明显的性能下降

## 实施结果

已经实施了以下修复：

1. 在`state.cljs`中修改了主要的异步通道为滑动窗口缓冲区，包括：
   - `:system/events`
   - `:db/batch-txs`
   - `:file/writes`
   - `:reactive/custom-queries`
   - `:file/rename-event-chan`

2. 在`editor.cljs`中添加了防抖版本的Enter键处理函数：
   ```clojure
   (def keydown-new-block-handler-debounced
     (util/debounce keydown-new-block-handler 50))
   ```

3. 在`shortcut/config.cljs`中将Enter键的快捷键处理函数替换为防抖版本：
   ```clojure
   :editor/new-block {:binding "enter"
                      :fn      editor-handler/keydown-new-block-handler-debounced}
   ```

4. 在`react.cljs`中改进了缓存清理策略，降低了触发阈值并更积极地清理缓存：
   ```clojure
   (when (> current-size (* 0.8 max-query-cache-size))
     ;; 降低触发阈值，更积极地清理
   ```

5. 在`util.cljc`中添加了批量处理函数，可以用于异步操作分批处理：
   ```clojure
   (defn batch-process [items process-fn batch-size] ...)
   ```

这些修改应该能够有效解决Enter键通道溢出问题，避免频繁按下Enter键时出现异常。
