"""Fixtures, tasks and reference answers.

Each task is phrased as a person asks in Chat and names the exact JSON it must print, so scoring does not depend on
formatting. The tasks lean on what changed under the model (pandas copy-on-write, frequency aliases, ffill, numpy 2
removals and scalar printing) and on what the first evaluation found (dayfirst on ISO dates). References are computed
with plain current idioms on the executor's own packages.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd


def build_fixtures(directory: Path) -> None:
    rng = np.random.default_rng(20261003)
    directory.mkdir(parents=True, exist_ok=True)
    regions = ["Bắc", "Trung", "Nam"]

    days = pd.date_range("2024-01-01", "2024-12-31", freq="D")
    sales = pd.DataFrame(
        {
            "Ngày": np.repeat(days[:60], 3),
            "Khu vực": regions * 60,
            "Doanh thu": rng.integers(100, 1000, 180).astype(float),
        }
    )
    sales.loc[rng.choice(180, 17, replace=False), "Doanh thu"] = np.nan
    sales.to_excel(directory / "doanh_thu.xlsx", index=False)

    pd.DataFrame({"Ngày": days.strftime("%Y-%m-%d"), "Số đơn": rng.integers(0, 50, len(days))}).to_csv(
        directory / "don_hang_ngay.csv", index=False
    )

    stamps = pd.Timestamp("2024-03-05") + pd.to_timedelta(rng.integers(0, 24 * 3600, 300), unit="s")
    pd.DataFrame(
        {
            "Thời điểm": pd.Series(stamps).sort_values().dt.strftime("%Y-%m-%d %H:%M:%S"),
            "Mã ticket": [f"T{i:04d}" for i in range(300)],
        }
    ).to_csv(directory / "ticket.csv", index=False)

    status = rng.choice(["Hoàn tất", "Hoàn tiền", "Đang giao"], 120, p=[0.6, 0.2, 0.2])
    pd.DataFrame(
        {
            "Mã đơn": [f"DH{i:04d}" for i in range(120)],
            "Trạng thái": status,
            "Số tiền": rng.integers(50_000, 2_000_000, 120),
        }
    ).to_excel(directory / "don_hang.xlsx", index=False)

    pd.DataFrame(
        {
            "Mã KH": [f"KH{i:03d}" for i in range(8)],
            "Họ tên": [
                "  nguyễn văn an ",
                "trần thị bình",
                " lê cường",
                "phạm dũng  ",
                "hoàng em",
                "vũ phương",
                " đặng giang",
                "bùi hà ",
            ],
            "Tỉnh": [" hà nội", "đà nẵng ", "hcm", " hải phòng ", "cần thơ", "huế", " hà nội ", "hcm "],
            "Tuổi": [31, 45, 28, 39, 52, 23, 36, 41],
            "Điểm": [7.5, 8.0, 6.5, 9.0, 5.5, 8.5, 7.0, 6.0],
        }
    ).to_excel(directory / "khach_hang.xlsx", index=False)

    price = pd.Series(rng.integers(20_000, 30_000, 40).astype(float))
    price[rng.choice(np.arange(1, 40), 9, replace=False)] = np.nan
    pd.DataFrame({"Ngày": pd.date_range("2024-05-01", periods=40, freq="D").strftime("%Y-%m-%d"), "Giá": price}).to_csv(
        directory / "gia.csv", index=False
    )

    x = np.linspace(0, 10, 41)
    pd.DataFrame({"x": x, "y": np.round(np.sqrt(x) * 3 + 1, 4)}).to_csv(directory / "duong_cong.csv", index=False)

    two_years = pd.date_range("2023-01-01", "2024-12-31", freq="D")
    pd.DataFrame(
        {"Ngày": two_years.strftime("%Y-%m-%d"), "Doanh thu": rng.integers(1_000, 9_000, len(two_years))}
    ).to_csv(directory / "doanh_thu_2_nam.csv", index=False)

    pd.DataFrame(
        {
            "Ngày": pd.date_range("2024-07-01", periods=30, freq="D").strftime("%Y-%m-%d"),
            "Tồn kho": rng.integers(100, 500, 30),
        }
    ).set_index("Ngày").to_csv(directory / "ton_kho.csv")

    pd.DataFrame(
        {
            "Nhân viên": [f"NV{i:02d}" for i in range(15)],
            "Phòng ban": rng.choice(["Kế toán", "Kinh doanh", "Kỹ thuật"], 15),
            "Lương": rng.integers(8, 40, 15) * 1_000_000,
        }
    ).to_excel(directory / "nhan_su.xlsx", index=False)


def reference(directory: Path) -> dict[str, object]:
    out: dict[str, object] = {}

    sales = pd.read_excel(directory / "doanh_thu.xlsx")
    sales["Doanh thu"] = sales["Doanh thu"].fillna(0)
    out["fill_then_total"] = {
        "zero_rows": int((sales["Doanh thu"] == 0).sum()),
        "totals": {str(k): float(v) for k, v in sales.groupby("Khu vực")["Doanh thu"].sum().items()},
    }

    daily = pd.read_csv(directory / "don_hang_ngay.csv", parse_dates=["Ngày"]).set_index("Ngày")
    out["monthly"] = [int(v) for v in daily["Số đơn"].resample("ME").sum()]

    tickets = pd.read_csv(directory / "ticket.csv", parse_dates=["Thời điểm"])
    counts = tickets["Thời điểm"].dt.hour.value_counts()
    out["hourly"] = [int(counts.get(hour, 0)) for hour in range(24)]

    orders = pd.read_excel(directory / "don_hang.xlsx")
    orders.loc[orders["Trạng thái"] == "Hoàn tiền", "Số tiền"] = 0
    out["conditional_zero"] = {"total": int(orders["Số tiền"].sum()), "zeroed": int((orders["Số tiền"] == 0).sum())}

    customers = pd.read_excel(directory / "khach_hang.xlsx")
    text = sorted(column for column in customers.columns if pd.api.types.is_string_dtype(customers[column]))
    for column in text:
        customers[column] = customers[column].str.strip().str.upper()
    out["text_columns"] = {"columns": text, "first_row": {column: customers[column].iloc[0] for column in text}}

    prices = pd.read_csv(directory / "gia.csv")
    out["ffill_mean"] = round(float(prices["Giá"].ffill().mean()), 2)

    curve = pd.read_csv(directory / "duong_cong.csv")
    out["trapezoid"] = round(float(np.trapezoid(curve["y"], curve["x"])), 4)

    revenue = pd.read_csv(directory / "doanh_thu_2_nam.csv", parse_dates=["Ngày"]).set_index("Ngày")
    out["quarterly"] = [int(v) for v in revenue["Doanh thu"].resample("QE").sum()]

    stock = pd.read_csv(directory / "ton_kho.csv", index_col="Ngày")["Tồn kho"]
    out["first_last"] = {"first": int(stock.iloc[0]), "last": int(stock.iloc[-1])}

    staff = pd.read_excel(directory / "nhan_su.xlsx")
    above = staff[staff["Lương"] > staff.groupby("Phòng ban")["Lương"].transform("mean")]
    out["above_mean"] = {str(row["Nhân viên"]): int(row["Lương"]) for _, row in above.iterrows()}
    return out


TASKS: dict[str, str] = {
    "fill_then_total": (
        "File doanh_thu.xlsx có các cột Ngày, Khu vực, Doanh thu; một số ô Doanh thu bị trống. Điền 0 vào các ô "
        "Doanh thu trống ngay trong bảng, rồi cho tôi biết có bao nhiêu dòng Doanh thu bằng 0 và tổng Doanh thu theo "
        'từng khu vực. In JSON {"answer": {"zero_rows": <số nguyên>, "totals": {"<khu vực>": <số thực>}}}.'
    ),
    "monthly": (
        "File don_hang_ngay.csv có cột Ngày và Số đơn cho cả năm 2024. Tính tổng số đơn của từng tháng. "
        'In JSON {"answer": [<12 số nguyên, tháng 1 đến tháng 12>]}.'
    ),
    "hourly": (
        "File ticket.csv ghi các ticket của ngày 2024-03-05, với hai cột Thời điểm và Mã ticket. Đếm số ticket theo "
        'từng giờ trong ngày, từ 0 giờ đến 23 giờ. In JSON {"answer": [<24 số nguyên>]}.'
    ),
    "conditional_zero": (
        "File don_hang.xlsx có Mã đơn, Trạng thái, Số tiền. Với các đơn có Trạng thái là 'Hoàn tiền', sửa Số tiền "
        "thành 0 trong bảng. Sau đó cho biết tổng Số tiền của cả bảng và số đơn có Số tiền bằng 0. "
        'In JSON {"answer": {"total": <số nguyên>, "zeroed": <số nguyên>}}.'
    ),
    "text_columns": (
        "File khach_hang.xlsx có cả cột chữ và cột số. Với mọi cột chữ: bỏ khoảng trắng thừa ở hai đầu và viết hoa "
        "toàn bộ. Cho biết tên các cột chữ và giá trị đã chuẩn hóa ở dòng đầu tiên của các cột đó. "
        'In JSON {"answer": {"columns": [<tên cột, sắp xếp tăng dần>], "first_row": {"<cột>": "<giá trị>"}}}.'
    ),
    "ffill_mean": (
        "File gia.csv có Ngày và Giá; một số ngày thiếu Giá. Điền giá còn thiếu bằng giá của ngày gần nhất trước đó, "
        'rồi tính giá trung bình, làm tròn 2 chữ số. In JSON {"answer": <số thực>}.'
    ),
    "trapezoid": (
        "File duong_cong.csv có hai cột x và y. Tính diện tích dưới đường cong y theo x bằng quy tắc hình thang của "
        'numpy, làm tròn 4 chữ số. In JSON {"answer": <số thực>}.'
    ),
    "quarterly": (
        "File doanh_thu_2_nam.csv có Ngày và Doanh thu cho năm 2023 và 2024. Tính tổng doanh thu theo từng quý. "
        'In JSON {"answer": [<8 số nguyên, từ quý 1/2023 đến quý 4/2024>]}.'
    ),
    "first_last": (
        "File ton_kho.csv có cột Ngày (làm chỉ mục) và Tồn kho. Đọc Ngày làm chỉ mục rồi cho biết tồn kho của dòng "
        'đầu tiên và dòng cuối cùng. In JSON {"answer": {"first": <số nguyên>, "last": <số nguyên>}}.'
    ),
    "above_mean": (
        "File nhan_su.xlsx có Nhân viên, Phòng ban, Lương. Liệt kê những nhân viên có lương cao hơn lương trung bình "
        'của phòng ban mình. In JSON {"answer": {"<mã nhân viên>": <lương, số nguyên>}}.'
    ),
}
