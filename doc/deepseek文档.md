思考模式
DeepSeek 模型支持思考模式：在输出最终回答之前，模型会先输出一段思维链内容，以提升最终答案的准确性。

思考模式开关与思考强度控制
控制参数（OpenAI 格式）	控制参数（Anthropic 格式）	控制参数（Responses API 格式）
思考模式开关(1)	{"thinking": {"type": "enabled/disabled"}}	{"reasoning": {"effort": "none/low/high/max"}}
(none 表示关闭思考模式)
思考强度控制(2)	{"reasoning_effort": "low/high/max"}	{"output_config": {"effort": "low/high/max"}}
(1) 思考模式默认打开，且 effort 默认为 high
(2) 用户设置的 effort 与模型推理 effort 映射表如下：

请求传入 effort	实际映射 effort
minimal	low
low	low
medium	high
high	high
xhigh	high
max	max
ultra	max
您在 OpenAI SDK 中使用 Chat Completion 设置 thinking 参数时，需要将 thinking 参数传入 extra_body 中：

response = client.chat.completions.create(
  model="deepseek-flash",
  # ...
  reasoning_effort="high",
  extra_body={"thinking": {"type": "enabled"}}
)

输入输出参数
思考模式不支持 temperature、presence_penalty、frequency_penalty 参数。请注意，为了兼容已有软件，设置参数不会报错，但也不会生效。

top_p 仅在思考模式下生效，有效取值范围为 0.95–1.0，低于 0.95 的取值会按 0.95 处理。在非思考模式下，该参数恒为 1.0，传入的值会被忽略。

在思考模式下，思维链内容通过 reasoning_content 参数返回，与 content 同级。在后续轮次的请求中，reasoning_content 是否需要回传、是否会被拼接进上下文，取决于请求是否携带 tools 参数：

若请求携带 tools 参数：历史轮次的 reasoning_content 均应回传给 API，并会被拼接进上下文。详见工具调用。
若请求未携带 tools 参数：reasoning_content 无需回传；即使传入 API，也会被忽略，不会拼接进上下文。详见多轮对话拼接。

JSON Output
在很多场景下，用户需要让模型严格按照 JSON 格式来输出，以实现输出的结构化，便于后续逻辑进行解析。

DeepSeek 提供了 JSON Output 功能，来确保模型输出合法的 JSON 字符串。

注意事项
设置 response_format 参数为 {'type': 'json_object'}。
用户传入的 system 或 user prompt 中必须含有 json 字样，并给出希望模型输出的 JSON 格式的样例，以指导模型来输出合法 JSON。
需要合理设置 max_tokens 参数，防止 JSON 字符串被中途截断。
在使用 JSON Output 功能时，API 有概率会返回空的 content。我们正在积极优化该问题，您可以尝试修改 prompt 以缓解此类问题。
样例代码
这里展示了使用 JSON Output 功能的完整 Python 代码：

import json
from openai import OpenAI

client = OpenAI(
    api_key="<your api key>",
    base_url="https://api.deepseek.com",
)

system_prompt = """
The user will provide some exam text. Please parse the "question" and "answer" and output them in JSON format. 

EXAMPLE INPUT: 
Which is the highest mountain in the world? Mount Everest.

EXAMPLE JSON OUTPUT:
{
    "question": "Which is the highest mountain in the world?",
    "answer": "Mount Everest"
}
"""

user_prompt = "Which is the longest river in the world? The Nile River."

messages = [{"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}]

response = client.chat.completions.create(
    model="deepseek-flash",
    messages=messages,
    response_format={
        'type': 'json_object'
    }
)

print(json.loads(response.choices[0].message.content))

模型将会输出：

{
    "question": "Which is the longest river in the world?",
    "answer": "The Nile River"
}