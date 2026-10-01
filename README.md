# maimaid

面向 maimai DX 玩家的本地优先工具，提供 iOS 和 Android 客户端，支持曲库、多档案、成绩、B50、进度、OCR、Diving Fish/LXNS 导入，以及手动云备份。

## 仓库结构

本仓库包含 `ios/`、`android/`、`shared/`（跨端协议），以及独立构建和发布曲库的 `static-builder/`、`static-worker/`。

统一账号与云服务已拆到 [rhythmeta-backend](https://github.com/rhythmeta/rhythmeta-backend)，运行于 Cloudflare Workers + D1 + R2。账号网站拆到 [rhythmeta-dashboard](https://github.com/rhythmeta/rhythmeta-dashboard)，入口为 [dash.rhythmeta.org](https://dash.rhythmeta.org)。

## 开发

需要 pnpm 10；iOS 使用 Xcode，目标 iOS 26+；Android 使用 JDK 17 和 Android SDK 37。

```sh
pnpm install --frozen-lockfile
pnpm test:static
pnpm typecheck:static
pnpm build:static
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

静态数据工作流直接读取公开上游，不再依赖后端。部署需要 `MAIMAID_STATIC_ASSETS_URL`、`CLOUDFLARE_ACCOUNT_ID` 和 `CLOUDFLARE_API_TOKEN` Secrets。

客户端默认连接 `https://api.rhythmeta.org`，登录网站为 `https://dash.rhythmeta.org`。Android 可用 `-PMAIMAID_BACKEND_URL=...` 和 `-PMAIMAID_BACKEND_AUTH_URL=...` 覆盖；iOS 在 Git 忽略的 `ios/Config/Secrets.xcconfig` 中配置 `BACKEND_URL` 和 `BACKEND_AUTH_URL`。

## 账号与备份

原有账号继续有效，迁移后需重新登录。手动备份将本地档案、成绩与游玩记录、歌单、收藏、设置编码为 protobuf + gzip 后保存到 R2，每个游戏保留最近三份。恢复会替换全部本地个人数据，并先保存回滚副本。静态曲库和凭据不进入备份。

旧逐条云同步、云端导入、公共歌单存储和多人游戏已停用；本地导入、成绩上传和歌单快照分享仍可使用。

## 致谢

感谢 Diving Fish、LXNS Coffee House、arcade-songs 提供社区数据；感谢 charaDiana、Keritial 等提供标注与资金支持。

## Donation

Your support is the greatest driving force behind our continued development and maintenance; we welcome donations of any amount.

ERC20: `0x1360a13425f5982dfe09a9f3b7046db80080c88d`

or just scan this QRCode:

<img width="400" alt="IMG_1378" src="https://github.com/user-attachments/assets/0d3e657b-6d2a-48e5-8652-3d0ee7107723" />

## 数据与版权

maimai 及其素材和商标属于 SEGA。本项目是独立社区工具，与 SEGA 无官方关联。
