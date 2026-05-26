# GridOpsAgent 汇报 PPT 启动说明

这是一个 HTML 横向翻页 PPT，不需要启动 GridOpsAgent 后端服务。

## 方式一：直接打开

直接双击打开：

```text
index.html
```

如果浏览器正常显示图片和翻页，这是最简单的方式。

## 方式二：用 Python 启动本地预览服务

进入 PPT 目录：

```powershell
cd E:\code\电网agent项目\GridOpsAgent-main\outputs\GridOpsAgent_v9_academic_progress_report
```

启动本地服务：

```powershell
python -m http.server 8020
```

然后浏览器打开：

```text
http://localhost:8020/
```

如果 `8020` 被占用，就换一个端口：

```powershell
python -m http.server 8021
```

对应打开：

```text
http://localhost:8021/
```

## 关闭方式

在运行 `python -m http.server` 的 PowerShell 窗口里按：

```text
Ctrl + C
```

看到命令行回到可输入状态，就说明 PPT 预览服务已经关闭。

## 翻页方式

- `→` / `PageDown` / 空格：下一页
- `←` / `PageUp`：上一页
- `ESC`：打开或关闭缩略图索引

## 当前文件结构

```text
GridOpsAgent_v9_academic_progress_report/
├─ index.html
├─ PPT启动说明.md
└─ images/
```

其中：

- `index.html` 是 PPT 主文件
- `images/` 是 PPT 使用的截图素材
- `PPT启动说明.md` 是这份说明
