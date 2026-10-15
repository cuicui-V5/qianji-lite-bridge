# Qianji-Lite-Bridge（钱迹前台语音与局域网记账网关）

一个运行在 Android 手机前台的轻量级个人记账网关工具，专为配合**钱迹 Android 版自动化记账接口**设计。

---

## 核心功能与亮点

1. **移动端极速语音记账（多模态直传）**：
   - 提供屏幕底部的超大“按住说话”按钮。
   - 使用 **Google 官方最新的 `com.google.genai.*` SDK** 直接将音频传给 **`gemini-3.5-flash-lite`** 模型进行多模态理解。
   - 自动结合当前系统时间解析相对时间（如“刚才”、“今天中午”），推导出结构化账单（支持一句话多笔消费拆分），无需本地离线 ASR。

2. **局域网 RESTful API 暴露（NanoHTTPD）**：
   - App 在前台常亮运行时，自动开启 `0.0.0.0:8848` HTTP 服务。
   - 支持局域网设备/自动化脚本调用：
     - `GET /api/v1/status`：获取当前网关状态与队列堆积量。
     - `POST /api/v1/bill/raw`：直接传入结构化账单 JSON 数组/单对象。
     - `POST /api/v1/bill/ai`：传入自然语言文本，由网关代调 Gemini 解析为账单。

3. **异步限流队列（严格 3.2s FIFO）**：
   - 遵循钱迹官方“3 秒内只能调用一次”的防刷限流规则。
   - 所有来自语音与局域网高并发请求统一进入协程 `Channel<BillItem>` 队列，HTTP 毫秒级响应 `200 OK (Queued)`。
   - 后台协程以 **3,200ms / 条** 的安全节奏依序触发 URL Scheme 唤起钱迹记账，绝不触发钱迹防刷阻断。

4. **极简前台模式**：
   - 无需复杂的后台保活服务、无障碍服务或悬浮窗权限。
   - 仅申请 `INTERNET` 与 `RECORD_AUDIO` 权限，进入页面自动设置 `FLAG_KEEP_SCREEN_ON` 常亮，退出即停止。

---

## 局域网 HTTP API 规范

### 1. 状态查询
* **路径**: `GET /api/v1/status`
* **响应示例**:
```json
{
  "code": 200,
  "status": "running",
  "port": 8848,
  "queue_size": 0,
  "version": "1.0.0"
}
```

### 2. 结构化账单直通入队
* **路径**: `POST /api/v1/bill/raw`
* **请求体** (支持单个对象或数组):
```json
[
  {
    "type": 0,
    "money": 28.5,
    "time": "2026-10-15 12:30:00",
    "remark": "瑞幸咖啡生椰拿铁",
    "catename": "三餐/::/午餐",
    "accountname": "微信",
    "bookname": ""
  }
]
```
* **响应示例**:
```json
{
  "code": 200,
  "message": "Queued",
  "count": 1,
  "queue_size": 1
}
```

### 3. 自然语言代调 AI 解析
* **路径**: `POST /api/v1/bill/ai`
* **请求体**:
```json
{
  "text": "中午在公司食堂吃午餐花了22元微信付的，下午坐地铁回家人人通刷卡4元"
}
```
* **响应示例**:
```json
{
  "code": 200,
  "message": "Queued",
  "count": 2,
  "bills": [...],
  "queue_size": 2
}
```

---

## 构建与 CI/CD

工程使用标准 Android Gradle 构建，并通过 GitHub Actions 自动化编译打包。

GitHub Actions 工作流定义于 `.github/workflows/android-build.yml`，在推送到 `main`/`master` 分支或手动触发时自动编译生成 Debug APK。
