/**
 * 健壮的 SSE 流解析器
 * 按 \n\n 分割完整事件，避免跨 chunk 丢事件类型
 */
async function parseSSEStream(response, handlers) {
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
        const {done, value} = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, {stream: true});
        // SSE 事件之间用 \n\n 分隔
        const events = buffer.split('\n\n');
        buffer = events.pop() || '';
        for (const block of events) {
            if (!block.trim()) continue;
            const lines = block.split('\n');
            let eventType = '';
            const dataLines = [];
            for (const line of lines) {
                if (line.startsWith('event:')) {
                    eventType = line.substring(6).trim();
                } else if (line.startsWith('data:')) {
                    dataLines.push(line.substring(5).trim());
                }
            }
            if (eventType && handlers[eventType]) {
                handlers[eventType](dataLines.join('\n'));
            }
        }
    }
}
