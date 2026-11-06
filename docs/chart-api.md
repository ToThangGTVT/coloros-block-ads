# Biểu đồ lưu lượng

Trang chủ khai báo `TrafficChart` trong `feature/home/src/main/res/layout/screen_home.xml`.
View này dùng MPAndroidChart v4.0.1 (`MPChartLib`) và nhận danh sách `TrafficDay`.

`core/designsystem` định dạng trục ngày theo ngôn ngữ của ứng dụng, đọc màu từ
theme XML và cung cấp mô tả từng ngày cho trình đọc màn hình. JitPack chỉ phục vụ
group `com.github.PhilJay.MPAndroidChart`.
