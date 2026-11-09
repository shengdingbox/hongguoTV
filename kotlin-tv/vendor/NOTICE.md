# 第三方源码

`hongguo.cjs` 是 `spider/js/红果果[短].js` 的原样副本，来源：
https://github.com/woshishiq1/drpys/tree/22261adfa31435e3b3ef5a730b8a2a75bcaf1715
取回于 2026-09-21。上游许可：GPL-3.0，全文见 `LICENSE.drpys`。
文件名仅为本地引用而调整，原始文件头和注释保持不变。

该文件只作为 Kotlin 移植的对照源，由 `../tools/signing-oracle.cjs` 在开发期读取以重新生成
`../core/src/test/resources/signing.json`。它不参与 APK 构建，也不会被打包进应用。
`core` 中的签名、Spade URL/密钥处理和 MP4 CENC 实现是对该源的 Kotlin 移植，见 `../NOTICE.md`。
