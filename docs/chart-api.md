# Chart API đã đối chiếu

COUI 1.1.0 không có component chart trong source phát hành hoặc trang components.
Theo yêu cầu người dùng, dùng MPAndroidChart tag v4.0.1, Apache-2.0 + NOTICE.

- `MPChartCompose/compose/Charts.kt`: BarChart(data, modifier, contentDescription,
  setup, update), update dành cho theme/Compose state.
- `ComposeInterop.kt`: rememberTypeface từ FontFamily và LocalFontFamilyResolver.
- `BarEntry.kt`: stacked constructor x, stackValues: List<Float>, data.
- `BarDataSet.kt`: colors, stackLabels, barCornerRadius, isDrawValuesEnabled.
- `BarChartRenderer.kt`: radius đổi từ dp sang px; dùng CardDefaults.CornerRadius.
- `AxisBase.kt`: IAxisValueFormatter, labelCount, granularity.

Chỉ core/designsystem phụ thuộc thư viện. Feature Home nhận TrafficDay và gọi
AppTrafficChart, không chứa màu hay import MPAndroidChart. Repository JitPack chỉ
cho group com.github.PhilJay.MPAndroidChart; version pin v4.0.1 ở catalog.

https://suqi8.github.io/coui/components/
https://github.com/PhilJay/MPAndroidChart/tree/v4.0.1
