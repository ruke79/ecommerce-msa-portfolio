#!/usr/bin/env python3
"""부하 테스트용 카탈로그 데이터 생성기 — 수천만 개까지.

두 가지 분류(--taxonomy):

  dept (기본)  대형 쇼핑몰의 백화점식 분류를 본뜬 내장 분류 — 대분류 20개 · 소분류 약 120개.
               이름 · 설명 · 옵션(의류 사이즈, 신발 치수, 저장 용량, 중량, 묶음 수, 책 형태 …) ·
               가격대가 소분류마다 다르다. 시드와 이름이 같은 대분류(fashion · books · food ·
               furniture · consumer electronics)는 그 id 를 그대로 쓰고, 소분류를 더한다.
  seed         저장소의 시드 CSV(상품 250개)를 템플릿으로 증식 — 예전 동작.

왜 분류가 중요한가: 카테고리 목록 · 필터 · 정렬의 비용은 **한 카테고리에 몇 개가 몰리는가**에
좌우된다. 시드 분류(소분류 15개)에 2천만 개를 넣으면 소분류 하나에 130만 개가 들어가 현실과
다른 문제를 재게 된다. 소분류 120개면 소분류당 약 17만 개다.

참조 무결성을 지킨다:
    category → subcategory → product → product_sku → product_size(sku_code 포함)
                                    └→ product_subcategory
    product_sku.color_id → product_color(시드 색상을 공유한다)

사용:
    # 용량 · 행 수만 미리 본다(아무것도 쓰지 않는다)
    ./ops/tools/generate-catalog-data.py --products 20000000 --estimate

    # PostgreSQL 로 직접 적재 — 대량은 이쪽만 현실적이다
    ./ops/tools/generate-catalog-data.py --products 20000000 \\
        --copy "postgresql://product:pw@localhost:5435/productdb?sslmode=require" --yes

    # 중간에 끊겼으면 같은 명령에 --resume — 이미 들어간 묶음 다음부터 잇는다
    # 부하 테스트가 끝나면 지운다(--id-offset 범위의 상품 · SKU · 사이즈 · 연결만)
    ./ops/tools/generate-catalog-data.py --products 20000000 --copy "…" --purge --yes

    # 배치 잡으로 적재 경로까지 검증하려면 CSV(소량만 — 배치 잡은 이미지를 SKU 마다 올린다)
    ./ops/tools/generate-catalog-data.py --products 5000 --out /tmp/bigcsv

메모리: 묶음(--chunk, 기본 2만 상품) 단위로 만들고 바로 COPY · 커밋한다. 상품 수와 상관없이
메모리는 묶음 하나만큼만 쓴다(예전에는 전부 메모리에 올려 수백만 개에서 멈췄다).

⚠️ 운영 DB 에 넣기 전에 --estimate 로 디스크를 확인할 것. 백만 개 이상은 --yes 없이는 시작하지 않는다.
"""
from __future__ import annotations

import argparse
import csv
import io
import random
import re
import sys
import time
from datetime import date, timedelta
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SEED = REPO / "catalog-service/src/main/resources/csv"
IMAGES = REPO / "catalog-service/src/main/resources/images"

# ── 내장 분류 ────────────────────────────────────────────────────────────────
#
# (대분류, 이미지 풀, 비중, 형용사, [(소분류, 옵션 종류, (최저가, 최고가), 명사 …)])
#
# 이미지 풀은 저장소에 있는 상품 이미지 폴더 이름이다(images/<풀>/). 새 대분류에 맞는 사진은
# 없으므로 가장 가까운 풀을 빌려 쓴다 — 부하 테스트용 데이터라 "그려지는가" 만 맞으면 된다.
# 비중은 대형 쇼핑몰의 상품 수 분포를 거칠게 따른다(책 · 의류 · 생활용품이 많고 악기 · 자동차가 적다).
TAXONOMY = [
    ("fashion", "fashion", 13,
     ["Classic", "Slim-Fit", "Relaxed", "Lightweight", "Organic Cotton", "Everyday", "Vintage", "Stretch"],
     [("men's clothing", "apparel", (12, 120), ["T-Shirt", "Oxford Shirt", "Chino Pants", "Hoodie", "Denim Jacket", "Polo Shirt", "Cargo Shorts"]),
      ("women's clothing", "apparel", (12, 150), ["Blouse", "Midi Dress", "Cardigan", "Wide-Leg Pants", "Knit Sweater", "Pleated Skirt", "Trench Coat"]),
      ("accessories", "single", (8, 90), ["Leather Belt", "Wool Scarf", "Baseball Cap", "Bucket Hat", "Sunglasses", "Card Wallet"]),
      ("shoes", "shoes", (25, 180), ["Running Shoes", "Leather Loafers", "Canvas Sneakers", "Chelsea Boots", "Sandals", "Hiking Boots"]),
      ("jewelry", "single", (15, 400), ["Pendant Necklace", "Hoop Earrings", "Charm Bracelet", "Signet Ring", "Stud Earrings"]),
      ("handbags", "single", (30, 350), ["Tote Bag", "Crossbody Bag", "Backpack", "Clutch", "Shoulder Bag"]),
      ("kids' clothing", "apparel", (8, 60), ["Graphic Tee", "Jogger Pants", "Rain Jacket", "Pajama Set", "Fleece Hoodie"]),
      ("luggage", "dimension", (60, 400), ["Carry-On Spinner", "Checked Suitcase", "Duffel Bag", "Garment Bag"])]),
    ("consumer electronics", "consumer electronics", 9,
     ["Wireless", "Portable", "Ultra-Slim", "Noise-Cancelling", "Smart", "High-Resolution", "Compact", "Pro"],
     [("smartphones", "storage", (150, 1300), ["Smartphone", "5G Phone", "Foldable Phone"]),
      ("laptops", "storage", (400, 2600), ["Laptop", "Ultrabook", "2-in-1 Laptop", "Gaming Laptop"]),
      ("wearable tech", "single", (40, 600), ["Smartwatch", "Fitness Tracker", "Smart Ring", "GPS Watch"]),
      ("headphones", "single", (20, 450), ["Over-Ear Headphones", "Earbuds", "Gaming Headset", "Bone Conduction Headphones"]),
      ("cameras", "single", (90, 2400), ["Mirrorless Camera", "Action Camera", "Instant Camera", "Camera Lens", "Dash Cam"]),
      ("tablets", "storage", (120, 1400), ["Tablet", "E-Reader", "Drawing Tablet"]),
      ("tv & video", "dimension", (150, 3000), ["4K TV", "Projector", "Streaming Stick", "Soundbar"]),
      ("computer accessories", "single", (8, 250), ["Mechanical Keyboard", "Wireless Mouse", "USB-C Hub", "Webcam", "Monitor Stand", "Portable SSD"])]),
    ("books", "books", 14,
     ["Bestselling", "Illustrated", "Annotated", "Award-Winning", "Revised", "Collected", "Complete", "Essential"],
     [("fiction", "format", (8, 35), ["Novel", "Mystery", "Thriller", "Short Story Collection", "Historical Novel"]),
      ("non-fiction", "format", (10, 45), ["Biography", "Memoir", "History", "Essay Collection", "Travel Guide"]),
      ("children's books", "format", (5, 25), ["Picture Book", "Bedtime Stories", "Activity Book", "First Reader"]),
      ("science fiction & fantasy", "format", (8, 30), ["Space Opera", "Epic Fantasy", "Dystopian Novel", "Time Travel Novel"]),
      ("business & money", "format", (12, 50), ["Leadership Guide", "Investing Handbook", "Startup Playbook", "Negotiation Guide"]),
      ("cookbooks", "format", (12, 45), ["Cookbook", "Baking Book", "Meal Prep Guide", "Vegetarian Cookbook"]),
      ("computers & technology", "format", (20, 80), ["Programming Guide", "System Design Handbook", "Data Science Primer", "Cloud Architecture Book"]),
      ("comics & graphic novels", "format", (8, 40), ["Graphic Novel", "Manga Volume", "Comic Anthology"])]),
    ("food", "food", 6,
     ["Organic", "Artisan", "Gluten-Free", "Single-Origin", "Cold-Pressed", "Small-Batch", "Roasted", "Premium"],
     [("snacks", "weight", (3, 25), ["Trail Mix", "Kettle Chips", "Granola Bars", "Dried Mango", "Rice Crackers"]),
      ("beverages", "pack", (4, 40), ["Sparkling Water", "Cold Brew Coffee", "Green Tea", "Kombucha", "Electrolyte Drink"]),
      ("dairy products", "weight", (3, 30), ["Aged Cheddar", "Greek Yogurt", "Butter", "Parmesan"]),
      ("pantry staples", "weight", (3, 35), ["Olive Oil", "Basmati Rice", "Pasta", "Honey", "Maple Syrup", "Sea Salt"]),
      ("coffee & tea", "weight", (6, 45), ["Whole Bean Coffee", "Ground Coffee", "Matcha Powder", "Loose Leaf Tea"])]),
    ("furniture", "furniture", 5,
     ["Mid-Century", "Solid Wood", "Modern", "Scandinavian", "Industrial", "Upholstered", "Minimalist", "Rustic"],
     [("living room furniture", "dimension", (80, 1800), ["Sofa", "Coffee Table", "TV Stand", "Accent Chair", "Bookshelf"]),
      ("bedroom furniture", "dimension", (60, 2000), ["Bed Frame", "Nightstand", "Dresser", "Wardrobe", "Mattress"]),
      ("office furniture", "dimension", (50, 1200), ["Standing Desk", "Office Chair", "Filing Cabinet", "Desk Organizer"]),
      ("outdoor furniture", "dimension", (60, 1500), ["Patio Set", "Hammock", "Adirondack Chair", "Garden Bench"]),
      ("kitchen & dining furniture", "dimension", (40, 1200), ["Dining Table", "Bar Stool", "Kitchen Cart", "Dining Chair Set"])]),
    ("home & kitchen", "home appliances", 9,
     ["Non-Stick", "Stainless Steel", "Stackable", "Dishwasher-Safe", "Ceramic", "Eco-Friendly", "Space-Saving", "Heavy-Duty"],
     [("cookware", "dimension", (15, 300), ["Frying Pan", "Dutch Oven", "Saucepan Set", "Wok", "Stock Pot"]),
      ("small appliances", "single", (25, 500), ["Air Fryer", "Blender", "Espresso Machine", "Rice Cooker", "Toaster Oven", "Electric Kettle"]),
      ("bedding", "dimension", (20, 300), ["Duvet Cover", "Sheet Set", "Pillow", "Weighted Blanket", "Mattress Topper"]),
      ("bath", "single", (8, 120), ["Bath Towel Set", "Shower Curtain", "Bath Mat", "Bathrobe"]),
      ("storage & organization", "dimension", (8, 150), ["Storage Bin", "Closet Organizer", "Drawer Divider", "Shoe Rack"]),
      ("home decor", "single", (10, 250), ["Wall Clock", "Table Lamp", "Throw Pillow", "Area Rug", "Picture Frame", "Scented Candle"]),
      ("vacuums & floor care", "single", (60, 800), ["Robot Vacuum", "Cordless Vacuum", "Steam Mop", "Carpet Cleaner"])]),
    ("beauty & personal care", "fashion", 7,
     ["Hydrating", "Fragrance-Free", "Vegan", "Long-Lasting", "Brightening", "Gentle", "Clinical-Strength", "Natural"],
     [("skin care", "volume", (8, 120), ["Moisturizer", "Serum", "Sunscreen", "Cleanser", "Eye Cream", "Toner"]),
      ("hair care", "volume", (6, 60), ["Shampoo", "Conditioner", "Hair Oil", "Styling Cream", "Hair Mask"]),
      ("makeup", "single", (6, 70), ["Lipstick", "Foundation", "Mascara", "Eyeshadow Palette", "Concealer"]),
      ("fragrance", "volume", (20, 250), ["Eau de Parfum", "Body Mist", "Cologne", "Solid Perfume"]),
      ("men's grooming", "single", (8, 150), ["Beard Trimmer", "Shaving Cream", "Electric Razor", "Aftershave Balm"])]),
    ("health & household", "food", 5,
     ["Daily", "Extra-Strength", "Unscented", "Family-Size", "Plant-Based", "Sugar-Free", "Advanced", "Value"],
     [("vitamins & supplements", "pack", (6, 70), ["Multivitamin", "Vitamin D3", "Omega-3", "Probiotic", "Magnesium"]),
      ("household supplies", "pack", (4, 50), ["Laundry Detergent", "Dish Soap", "Paper Towels", "Trash Bags", "All-Purpose Cleaner"]),
      ("medical supplies", "single", (5, 200), ["Thermometer", "Blood Pressure Monitor", "First Aid Kit", "Knee Brace"]),
      ("oral care", "pack", (3, 120), ["Electric Toothbrush", "Whitening Toothpaste", "Water Flosser", "Mouthwash"])]),
    ("toys & games", "books", 5,
     ["Educational", "Classic", "Collectible", "Interactive", "Wooden", "Deluxe", "Family", "STEM"],
     [("building toys", "single", (10, 300), ["Building Set", "Magnetic Tiles", "Marble Run", "Construction Kit"]),
      ("board games", "single", (12, 80), ["Strategy Game", "Party Game", "Cooperative Game", "Card Game", "Puzzle 1000 Pieces"]),
      ("dolls & action figures", "single", (8, 90), ["Action Figure", "Fashion Doll", "Plush Toy", "Dollhouse"]),
      ("outdoor play", "single", (15, 400), ["Scooter", "Water Blaster", "Trampoline", "Kids Bike"]),
      ("baby & toddler toys", "single", (6, 60), ["Stacking Rings", "Activity Cube", "Teething Toy", "Musical Toy"])]),
    ("sports & outdoors", "fashion", 6,
     ["Lightweight", "Waterproof", "Ergonomic", "Adjustable", "Breathable", "Insulated", "Foldable", "Rugged"],
     [("exercise & fitness", "single", (10, 900), ["Yoga Mat", "Adjustable Dumbbells", "Resistance Bands", "Exercise Bike", "Kettlebell"]),
      ("camping & hiking", "dimension", (15, 600), ["Tent", "Sleeping Bag", "Hiking Backpack", "Camping Stove", "Headlamp"]),
      ("cycling", "single", (10, 2500), ["Road Bike", "Bike Helmet", "Bike Lock", "Cycling Gloves", "Bike Light"]),
      ("team sports", "single", (10, 200), ["Soccer Ball", "Basketball", "Baseball Glove", "Tennis Racket"]),
      ("water sports", "single", (15, 900), ["Paddle Board", "Snorkel Set", "Wetsuit", "Swim Goggles"]),
      ("sports apparel", "apparel", (15, 150), ["Running Shorts", "Compression Leggings", "Training Jacket", "Sports Bra"])]),
    ("automotive", "consumer electronics", 3,
     ["Universal", "All-Weather", "Heavy-Duty", "Quick-Install", "OEM-Grade", "Portable", "Premium", "Rust-Proof"],
     [("car electronics", "single", (15, 600), ["Car Stereo", "Backup Camera", "Phone Mount", "Jump Starter"]),
      ("car care", "volume", (5, 80), ["Car Wax", "Interior Cleaner", "Microfiber Towels", "Tire Shine"]),
      ("tools & equipment", "single", (10, 300), ["Tire Inflator", "Socket Set", "Car Jack", "OBD2 Scanner"]),
      ("interior accessories", "single", (8, 150), ["Seat Cover", "Floor Mats", "Trunk Organizer", "Steering Wheel Cover"])]),
    ("pet supplies", "food", 4,
     ["Grain-Free", "Durable", "Orthopedic", "Washable", "Natural", "Calming", "Interactive", "Veterinarian-Approved"],
     [("dog supplies", "weight", (5, 200), ["Dry Dog Food", "Dog Bed", "Chew Toy", "Dog Leash", "Dog Treats"]),
      ("cat supplies", "weight", (4, 180), ["Cat Litter", "Cat Tree", "Wet Cat Food", "Scratching Post", "Cat Toy"]),
      ("fish & aquatic", "single", (5, 300), ["Aquarium Kit", "Fish Food", "Water Filter", "Aquarium Heater"]),
      ("small animals", "single", (5, 120), ["Hamster Cage", "Rabbit Hay", "Guinea Pig Bedding"])]),
    ("baby", "books", 3,
     ["Soft", "BPA-Free", "Hypoallergenic", "Convertible", "Lightweight", "Organic", "Adjustable", "Travel"],
     [("diapering", "pack", (8, 70), ["Diapers", "Baby Wipes", "Diaper Bag", "Changing Pad"]),
      ("nursery", "dimension", (30, 900), ["Crib", "Baby Monitor", "Rocking Chair", "Nursery Dresser"]),
      ("feeding", "single", (5, 250), ["Baby Bottle", "High Chair", "Bottle Warmer", "Bib Set"]),
      ("strollers & car seats", "single", (80, 1200), ["Stroller", "Car Seat", "Baby Carrier", "Travel System"])]),
    ("office products", "books", 3,
     ["Recycled", "Refillable", "Quick-Dry", "Ergonomic", "Professional", "Heavy-Duty", "Archival", "Compact"],
     [("writing supplies", "pack", (3, 60), ["Gel Pens", "Fountain Pen", "Highlighters", "Mechanical Pencils"]),
      ("paper products", "pack", (4, 50), ["Notebook", "Sticky Notes", "Printer Paper", "Planner"]),
      ("office electronics", "single", (30, 900), ["Laser Printer", "Document Scanner", "Label Maker", "Paper Shredder"]),
      ("desk accessories", "single", (5, 120), ["Desk Lamp", "Monitor Riser", "Desk Mat", "Cable Organizer"])]),
    ("tools & home improvement", "home appliances", 5,
     ["Cordless", "Brushless", "Professional", "Compact", "Heavy-Duty", "Precision", "Magnetic", "Weatherproof"],
     [("power tools", "single", (40, 600), ["Cordless Drill", "Circular Saw", "Impact Driver", "Angle Grinder", "Jigsaw"]),
      ("hand tools", "single", (8, 200), ["Screwdriver Set", "Claw Hammer", "Wrench Set", "Utility Knife", "Tape Measure"]),
      ("lighting & ceiling fans", "single", (15, 500), ["LED Bulbs", "Ceiling Fan", "Pendant Light", "Motion Sensor Light"]),
      ("electrical", "pack", (5, 150), ["Smart Plug", "Extension Cord", "Surge Protector", "Light Switch"]),
      ("hardware", "pack", (3, 80), ["Wall Anchors", "Cabinet Hinges", "Door Handle", "Shelf Brackets"])]),
    ("garden & outdoor", "furniture", 3,
     ["Weather-Resistant", "Solar-Powered", "Raised", "Self-Watering", "Galvanized", "Collapsible", "Ergonomic", "Native"],
     [("gardening", "single", (5, 300), ["Raised Garden Bed", "Pruning Shears", "Garden Hose", "Seed Starter Kit", "Planter"]),
      ("grills & outdoor cooking", "single", (40, 1500), ["Gas Grill", "Pellet Smoker", "Pizza Oven", "Grill Tool Set"]),
      ("outdoor decor", "single", (10, 300), ["String Lights", "Outdoor Rug", "Fire Pit", "Bird Feeder"]),
      ("lawn care", "single", (20, 900), ["Lawn Mower", "Leaf Blower", "String Trimmer", "Sprinkler"])]),
    ("musical instruments", "books", 2,
     ["Beginner", "Handcrafted", "Professional", "Portable", "Studio", "Vintage-Style", "Weighted", "Acoustic"],
     [("guitars", "single", (80, 2500), ["Acoustic Guitar", "Electric Guitar", "Bass Guitar", "Ukulele"]),
      ("keyboards & pianos", "single", (100, 3000), ["Digital Piano", "MIDI Keyboard", "Synthesizer"]),
      ("drums & percussion", "single", (30, 1500), ["Electronic Drum Kit", "Cajon", "Snare Drum", "Practice Pad"]),
      ("studio recording", "single", (30, 900), ["Audio Interface", "Condenser Microphone", "Studio Monitors", "Pop Filter"])]),
    ("video games", "consumer electronics", 3,
     ["Limited Edition", "Wireless", "Ergonomic", "RGB", "Pro", "Deluxe", "Rechargeable", "Low-Latency"],
     [("consoles", "storage", (200, 700), ["Game Console", "Handheld Console", "Retro Console"]),
      ("games", "format", (10, 70), ["Action Game", "Racing Game", "RPG", "Puzzle Game", "Sports Game"]),
      ("gaming accessories", "single", (10, 300), ["Controller", "Charging Dock", "Gaming Mouse", "Capture Card"])]),
    ("arts & crafts", "books", 2,
     ["Professional", "Non-Toxic", "Acid-Free", "Assorted", "Beginner", "Archival", "Water-Based", "Premium"],
     [("painting supplies", "pack", (5, 150), ["Acrylic Paint Set", "Watercolor Set", "Paint Brushes", "Canvas Panels"]),
      ("sewing & knitting", "pack", (4, 400), ["Sewing Machine", "Yarn Bundle", "Knitting Needles", "Fabric Bundle"]),
      ("scrapbooking", "pack", (4, 60), ["Sticker Set", "Washi Tape", "Scrapbook Album", "Craft Punch"])]),
    ("industrial & scientific", "home appliances", 1,
     ["Lab-Grade", "Calibrated", "Industrial", "ESD-Safe", "Precision", "Chemical-Resistant", "Digital", "Certified"],
     [("lab equipment", "single", (10, 1500), ["Digital Scale", "Microscope", "Magnetic Stirrer", "Pipette Set"]),
      ("safety equipment", "pack", (5, 200), ["Safety Glasses", "Work Gloves", "Respirator Mask", "Ear Protection"]),
      ("test & measurement", "single", (15, 900), ["Digital Multimeter", "Infrared Thermometer", "Laser Distance Meter"])]),
]

# 옵션 종류 → (값 목록, 고르는 개수 범위, 연속 구간인가, SKU(색상) 개수 범위, 값마다 가격 배율)
OPTIONS = {
    "apparel":   (["XS", "S", "M", "L", "XL", "XXL"], (3, 5), True, (2, 4), 0.0),
    "shoes":     (["230", "240", "250", "260", "270", "280", "290"], (4, 6), True, (1, 3), 0.0),
    "storage":   (["64GB", "128GB", "256GB", "512GB", "1TB"], (2, 3), True, (1, 3), 0.18),
    "volume":    (["30ml", "50ml", "100ml", "200ml", "500ml"], (1, 2), True, (1, 1), 0.6),
    "weight":    (["250g", "500g", "1kg", "2kg"], (1, 3), True, (1, 1), 0.7),
    "pack":      (["1 Pack", "2 Pack", "4 Pack", "12 Pack"], (1, 3), True, (1, 1), 0.8),
    "format":    (["Paperback", "Hardcover", "eBook"], (1, 3), False, (1, 1), 0.3),
    "dimension": (["Small", "Medium", "Large"], (1, 3), True, (1, 3), 0.35),
    "single":    ([""], (1, 1), True, (1, 3), 0.0),
}

SYLLABLES = ["ar", "bel", "cor", "da", "el", "fin", "gal", "ho", "is", "jun", "ka", "lu", "mer", "no", "or",
             "pa", "qui", "ro", "sol", "ta", "ul", "ve", "wen", "xa", "yo", "zen", "vi", "tri", "lo", "nex"]
BRAND_TAILS = ["", "", "", " Co.", " Labs", " Home", " & Sons", " Studio", " Works", " Goods"]

FEATURES = [
    "Designed for everyday use and built to last.",
    "Backed by a one-year limited warranty.",
    "Thoughtfully packaged with recyclable materials.",
    "Tested for quality and safety before shipping.",
    "Easy to clean and simple to maintain.",
    "A customer favorite for gifting.",
    "Made with responsibly sourced materials.",
    "Fits seamlessly into any routine.",
    "Includes everything you need to get started.",
    "Compact enough to take anywhere.",
]
REFUND = ["30 days", "30 days", "30 days", "14 days", "60 days", "90 days"]


def brand_name(rng: random.Random) -> str:
    n = rng.choice((2, 2, 3))
    word = "".join(rng.choice(SYLLABLES) for _ in range(n)).capitalize()
    return word + rng.choice(BRAND_TAILS)


def slugify(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def size_sku_code(style: str, size: str, pos: int) -> str:
    """V17 의 product.assign_size_sku_codes() 와 같은 규칙 — DB 함수를 수천만 행에 돌리지 않으려고 여기서 만든다."""
    raw = size.strip()
    if raw == "":
        return style
    norm = re.sub(r"[^A-Z0-9]+", "-", raw.upper()).strip("-")
    return f"{style}-S{pos}" if norm == "" else f"{style}-{norm}"


def sku_prefix(category_name: str) -> str:
    """ProductService.skuPrefix 와 같은 규칙."""
    alnum = re.sub(r"[^A-Za-z0-9]", "", category_name or "").upper()
    return alnum[:3] if len(alnum) >= 3 else "PRD"


def read_csv(name: str) -> tuple[list[str], list[dict]]:
    path = SEED / name
    if not path.exists() or path.stat().st_size == 0:
        return [], []
    with path.open(encoding="utf-8", newline="") as fh:
        reader = csv.DictReader(fh)
        rows = list(reader)
        return (reader.fieldnames or []), rows


def classpath_images() -> dict[str, list[str]]:
    """배치 잡이 읽는 형식(images\\<풀>\\Image_N.jpg)의 상대 경로 — CSV 모드용."""
    pools = {}
    if IMAGES.is_dir():
        for d in sorted(IMAGES.iterdir()):
            files = sorted(f.name for f in d.iterdir() if f.is_file()) if d.is_dir() else []
            if files:
                pools[d.name] = [f"images\\{d.name}\\{f}" for f in files]
    return pools


# ── 분류 준비 ────────────────────────────────────────────────────────────────

class Plan:
    """적재할 카테고리 · 소분류 · 색상 · 이미지 풀. id 는 DB(또는 시드 CSV)의 같은 이름을 먼저 찾는다."""

    def __init__(self, taxonomy: str):
        self.taxonomy = taxonomy
        self.categories: list[dict] = []      # {id, name, slug, new}
        self.subcats: list[dict] = []         # {id, name, slug, category_id, kind, price, nouns, new}
        self.depts: list[dict] = []           # {category, pool, weight, adjectives, subcats}
        self.color_ids: list[int] = []
        self.images: dict[str, list[str]] = {}
        self.seed_products: list[dict] = []

    def build(self, existing_cats: dict[str, int], existing_subs: dict[tuple[int, str], int],
              next_cat: int, next_sub: int):
        if self.taxonomy == "seed":
            return self._build_seed(existing_cats, existing_subs)
        for name, pool, weight, adjectives, subs in TAXONOMY:
            cid = existing_cats.get(name)
            if cid is None:
                cid, next_cat = next_cat, next_cat + 1
                self.categories.append({"id": cid, "name": name, "slug": slugify(name), "new": True})
            else:
                self.categories.append({"id": cid, "name": name, "slug": slugify(name), "new": False})
            dept = {"category_id": cid, "category_name": name, "pool": pool, "weight": weight,
                    "adjectives": adjectives, "subcats": []}
            for sname, kind, price, nouns in subs:
                sid = existing_subs.get((cid, sname))
                new = sid is None
                if new:
                    sid, next_sub = next_sub, next_sub + 1
                sc = {"id": sid, "name": sname, "slug": slugify(sname), "category_id": cid, "kind": kind,
                      "price": price, "nouns": nouns, "new": new}
                self.subcats.append(sc)
                dept["subcats"].append(sc)
            self.depts.append(dept)

    def _build_seed(self, existing_cats, existing_subs):
        _, cats = read_csv("categories.csv")
        _, subs = read_csv("subcategories.csv")
        _, self.seed_products = read_csv("products.csv")
        if not self.seed_products:
            sys.exit("ERROR: products.csv 가 비어 있어 seed 분류로 생성할 수 없습니다")
        for c in cats:
            self.categories.append({"id": int(c["category_id"]), "name": c["category_name"],
                                    "slug": c["slug"], "new": False})
        by_cat: dict[int, list[dict]] = {}
        for s in subs:
            sc = {"id": int(s["subcategory_id"]), "name": s["subcategory_name"], "slug": s["slug"],
                  "category_id": int(s["category_id"]), "kind": None, "new": False}
            self.subcats.append(sc)
            by_cat.setdefault(sc["category_id"], []).append(sc)
        self.seed_by_cat = by_cat

    def category_name(self, cid: int) -> str:
        return next((c["name"] for c in self.categories if c["id"] == cid), "")


# ── 생성 ─────────────────────────────────────────────────────────────────────

def rand_date(rng: random.Random, span_days: int = 730) -> str:
    # 날짜를 넓게 퍼뜨린다 — 한 날짜에 몰리면 "최신순" 정렬 · 파티션 프루닝 효과를 잴 수 없다(docs/09).
    return (date.today() - timedelta(days=rng.randrange(span_days))).isoformat()


def popularity(rng: random.Random) -> int:
    # 판매량은 긴 꼬리다 — 대부분 0~수십, 소수가 수만. 균등 분포면 "많이 팔린 순" 이 의미 없어진다.
    return min(100_000, int(rng.paretovariate(1.25) * 4) - 4)


class Generator:
    def __init__(self, plan: Plan, seed: int):
        self.plan = plan
        self.seed = seed
        if plan.taxonomy == "dept":
            self.weights = []
            for d in plan.depts:
                self.weights.append(d["weight"])
        self.brands_cache: dict[int, list[str]] = {}

    def brands_for(self, cid: int, rng: random.Random) -> list[str]:
        # 대분류마다 브랜드 400개 — 브랜드 필터 · 집계의 카디널리티가 현실적이 된다.
        if cid not in self.brands_cache:
            brng = random.Random(f"{self.seed}:brands:{cid}")
            self.brands_cache[cid] = sorted({brand_name(brng) for _ in range(500)})[:400]
        return self.brands_cache[cid]

    def chunk(self, start: int, count: int, pid0: int, sku0: int, size0: int, link0: int):
        """상품 [start, start+count) 를 만든다. pid0 등은 다음에 쓸 id."""
        rng = random.Random(f"{self.seed}:{start}")
        out = {"product": [], "product_sku": [], "product_size": [], "product_subcategory": []}
        sku_id, size_id, link_id = sku0, size0, link0
        for i in range(count):
            pid = pid0 + i
            if self.plan.taxonomy == "dept":
                p, sc, kind = self._dept_product(rng, pid)
            else:
                p, sc, kind = self._seed_product(rng, pid, start + i)
            out["product"].append(p)
            out["product_subcategory"].append((link_id, pid, sc["id"]))
            link_id += 1

            values, pick, contiguous, nsku, step = OPTIONS[kind]
            n_opt = rng.randint(*pick)
            if contiguous:
                first = rng.randrange(0, len(values) - n_opt + 1)
                opts = values[first:first + n_opt]
            else:
                chosen = set(rng.sample(values, n_opt))
                opts = [v for v in values if v in chosen]
            base = p["_price"]
            prefix = sku_prefix(self.plan.category_name(p["category_id"]))
            for color in rng.sample(self.plan.color_ids, min(rng.randint(*nsku), len(self.plan.color_ids))):
                style = f"{prefix}-{sku_id:06d}"
                pool = self.plan.images.get(p["_pool"]) or next(iter(self.plan.images.values()), [])
                imgs = rng.sample(pool, min(len(pool), rng.randint(2, 4))) if pool else []
                discount = 0 if rng.random() < 0.7 else rng.randrange(5, 45, 5)
                out["product_sku"].append((sku_id, style, imgs, discount, popularity(rng), color, pid, rand_date(rng)))
                for pos, opt in enumerate(opts, start=1):
                    price = round(base * (1 + step * (pos - 1)), 2)
                    qty = 0 if rng.random() < 0.05 else rng.randrange(1, 300)
                    out["product_size"].append((size_id, opt, qty, f"{price:.2f}", sku_id, size_sku_code(style, opt, pos)))
                    size_id += 1
                sku_id += 1
        return out, (sku_id, size_id, link_id)

    def _dept_product(self, rng: random.Random, pid: int):
        d = rng.choices(self.plan.depts, weights=self.weights)[0]
        sc = rng.choice(d["subcats"])
        brand = rng.choice(self.brands_for(d["category_id"], rng))
        noun = rng.choice(sc["nouns"])
        adj = rng.choice(d["adjectives"])
        model = f"{rng.choice('ABCDEFGHJKLMNPRSTVXZ')}{rng.randrange(10, 999)}"
        name = f"{brand} {adj} {noun} {model}"
        lo, hi = sc["price"]
        # 가격은 로그 균등 — 싼 상품이 많고 비싼 상품이 적다
        price = round(lo * (hi / lo) ** rng.random(), 2)
        desc = (f"{adj} {noun.lower()} by {brand} from our {sc['name']} collection. "
                + " ".join(rng.sample(FEATURES, rng.randint(2, 4))))
        return ({
            "product_id": pid,
            "name": name,
            "description": desc[:1000],
            "brand": brand,
            "slug": f"{slugify(name)}-{pid}",
            "category_id": d["category_id"],
            "refundpolicy": rng.choice(REFUND),
            "rating": round(min(5.0, max(1.0, rng.triangular(1.0, 5.0, 4.4))), 1),
            "shipping": "0.00" if rng.random() < 0.6 else f"{rng.choice((2.99, 3.99, 4.99, 6.99, 9.99)):.2f}",
            "createdat": rand_date(rng),
            "_price": price,
            "_pool": d["pool"],
        }, sc, sc["kind"])

    def _seed_product(self, rng: random.Random, pid: int, i: int):
        tpl = self.plan.seed_products[i % len(self.plan.seed_products)]
        cid = int(tpl["category_id"])
        pool = self.plan.seed_by_cat.get(cid) or self.plan.subcats
        sc = rng.choice(pool)
        cat = self.plan.category_name(cid)
        return ({
            "product_id": pid,
            "name": f'{tpl["name"]} #{pid}',
            "description": tpl["description"],
            "brand": tpl["brand"],
            "slug": f'{tpl["slug"]}-{pid}',
            "category_id": cid,
            "refundpolicy": tpl["refund_policy"],
            "rating": tpl["rating"],
            "shipping": tpl["shipping"],
            "createdat": rand_date(rng),
            "_price": round(rng.uniform(5, 200), 2),
            "_pool": cat,
        }, sc, "apparel" if cat == "fashion" else "single")


# ── 출력: PostgreSQL COPY ────────────────────────────────────────────────────

def pg_array(items: list[str]) -> str:
    """text[] 리터럴. 백슬래시(윈도우 경로)와 따옴표는 배열 안에서 이스케이프한다."""
    return "{" + ",".join('"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"' for s in items) + "}"


COLUMNS = {
    "product": ["product_id", "name", "description", "brand", "slug", "category_id", "refundpolicy",
                "rating", "shipping", "createdat", "version", "status"],
    "product_sku": ["skuproduct_id", "sku", "images", "discount", "sold", "color_id", "product_id",
                    "createdat", "version"],
    "product_size": ["size_id", "size", "quantity", "price", "skuproduct_id", "sku_code", "version"],
    "product_subcategory": ["proc_subcategory_id", "product_id", "subcategory_id", "version"],
}


def copy_rows(cur, table: str, rows) -> int:
    buf = io.StringIO()
    w = csv.writer(buf)
    n = 0
    for r in rows:
        w.writerow(r)
        n += 1
    buf.seek(0)
    cur.copy_expert(f"COPY product.{table} ({','.join(COLUMNS[table])}) FROM STDIN WITH CSV", buf)
    return n


def pg_rows(table: str, rows):
    if table == "product":
        for p in rows:
            yield [p["product_id"], p["name"], p["description"], p["brand"], p["slug"], p["category_id"],
                   p["refundpolicy"], p["rating"], p["shipping"], p["createdat"], 0, "ACTIVE"]
    elif table == "product_sku":
        for sid, sku, imgs, disc, sold, color, pid, created in rows:
            yield [sid, sku, pg_array(imgs), disc, sold, color, pid, created, 0]
    elif table == "product_size":
        for r in rows:
            yield [*r, 0]
    else:
        for r in rows:
            yield [*r, 0]


def check_placeholder(url: str) -> str:
    """자리 표시 이미지 주소 — 같은 출처 경로(/…) 또는 http(s). 장바구니 · 주문 줄의 image 가 varchar(255) 라 그 안으로."""
    if not (url.startswith("/") or re.match(r"^https?://", url)):
        sys.exit("ERROR: --placeholder-image 는 /경로 또는 http(s):// 주소여야 한다")
    if len(url) > 255 or re.search(r'[\s,"\\{}]', url):
        sys.exit("ERROR: --placeholder-image 는 255자 이하, 공백 · 쉼표 · 따옴표 · 백슬래시 · 중괄호 없이")
    return url


class PgSink:
    def __init__(self, dsn: str, placeholder: str | None = None):
        try:
            import psycopg2
        except ImportError:
            sys.exit("ERROR: --copy 에는 psycopg2 가 필요합니다  (pip install psycopg2-binary)")
        self.conn = psycopg2.connect(dsn)
        self.conn.autocommit = False
        self.placeholder = placeholder

    def scalar(self, sql: str, args=()):
        with self.conn.cursor() as cur:
            cur.execute(sql, args)
            row = cur.fetchone()
        self.conn.commit()
        return row[0] if row else None

    def rows(self, sql: str, args=()):
        with self.conn.cursor() as cur:
            cur.execute(sql, args)
            out = cur.fetchall()
        self.conn.commit()
        return out

    def prepare(self, plan: Plan, offset: int):
        """같은 이름의 카테고리 · 소분류를 찾고, 없으면 만든다. 색상 · 이미지 URL 은 DB 에 있는 것을 쓴다."""
        cats = {name: cid for cid, name in self.rows("SELECT category_id, category_name FROM product.category")}
        subs = {(cid, name): sid for sid, cid, name in
                self.rows("SELECT subcategory_id, category_id, subcategory_name FROM product.subcategory")}
        next_cat = (self.scalar("SELECT coalesce(max(category_id), 0) FROM product.category") or 0) + 1
        next_sub = (self.scalar("SELECT coalesce(max(subcategory_id), 0) FROM product.subcategory") or 0) + 1
        plan.build(cats, subs, next_cat, next_sub)

        plan.color_ids = [r[0] for r in self.rows("SELECT color_id FROM product.product_color ORDER BY color_id")]
        if not plan.color_ids and self.placeholder:
            plan.color_ids = self.seed_colors()
        if not plan.color_ids:
            sys.exit("ERROR: product.product_color 가 비어 있다 — 시드 배치 잡을 먼저 한 번 돌린다(색상 · 이미지 URL 을 만든다).\n"
                     "       이미지 저장소(S3)가 없는 환경(데모)이면 --placeholder-image 로 색상을 시드 CSV 에서 넣는다")

        # 이미지는 시드 배치가 S3 에 올린 URL 을 대분류(이미지 풀)별로 재사용한다. 예전 --copy 는 CSV 의
        # 클래스패스 경로(images\food\…)를 그대로 넣어 화면에서 이미지가 전부 깨졌다.
        found: dict[str, set[str]] = {}
        for cname, url in self.rows(
                "SELECT c.category_name, u FROM product.product_sku k "
                "JOIN product.product p ON p.product_id = k.product_id "
                "JOIN product.category c ON c.category_id = p.category_id, unnest(k.images) u "
                "WHERE p.product_id <= %s LIMIT 5000", (offset,)):
            if url:
                found.setdefault(cname, set()).add(url)
        plan.images = {k: sorted(v) for k, v in found.items()}
        if not plan.images and self.placeholder:
            # 풀 하나 — Generator 는 대분류 풀이 없으면 첫 풀을 쓴다. SKU 마다 이 한 장.
            print(f"  기존 상품 이미지 URL 이 없다 — 모든 SKU 에 자리 표시 이미지({self.placeholder})")
            plan.images = {"placeholder": [self.placeholder]}
        if not plan.images:
            print("  ! 기존 상품 이미지 URL 이 없다 — 클래스패스 경로를 넣는다(화면에서 이미지가 보이지 않는다).\n"
                  "    시드 배치 잡을 먼저 돌리면 그 URL 을 재사용한다. 저장소가 없으면 --placeholder-image.", file=sys.stderr)
            plan.images = classpath_images()

        with self.conn.cursor() as cur:
            for c in plan.categories:
                if c["new"]:
                    cur.execute("INSERT INTO product.category (category_id, category_name, slug, createdat, version) "
                                "VALUES (%s, %s, %s, now(), 0) ON CONFLICT (category_id) DO NOTHING",
                                (c["id"], c["name"], c["slug"]))
            for s in plan.subcats:
                if s["new"]:
                    cur.execute("INSERT INTO product.subcategory (subcategory_id, subcategory_name, slug, category_id, "
                                "createdat, version) VALUES (%s, %s, %s, %s, now(), 0) ON CONFLICT (subcategory_id) DO NOTHING",
                                (s["id"], s["name"], s["slug"], s["category_id"]))
        self.conn.commit()
        new_c = sum(c["new"] for c in plan.categories)
        new_s = sum(s["new"] for s in plan.subcats)
        print(f"  카테고리 {len(plan.categories)}개(새로 {new_c}) · 소분류 {len(plan.subcats)}개(새로 {new_s}) · "
              f"색상 {len(plan.color_ids)}개 · 이미지 풀 {len(plan.images)}개")

    def seed_colors(self) -> list[int]:
        """시드 CSV 의 색상(509개)을 그대로 넣는다 — 견본 이미지는 자리 표시 이미지. 시드 배치 잡 없이 적재하는 길(데모).

        🐛 예전: 이미지 저장소가 없는 데모 클러스터에서는 시드 배치 잡이 S3 쓰기 점검에서 멈추고, 이 생성기는 색상이
           없어 멈췄다 — 상품을 넣을 길이 없었다(2026-10-17).
        [JA] シード CSV の色をそのまま入れる(見本画像はプレースホルダー)。S3 のないデモではシードバッチも生成器も止まっていた。
        """
        _, colors = read_csv("product_colors.csv")
        if not colors:
            sys.exit("ERROR: product_colors.csv 가 비어 있다")
        with self.conn.cursor() as cur:
            for c in colors:
                cur.execute("INSERT INTO product.product_color (color_id, color, colorimage, createdat, version) "
                            "VALUES (%s, %s, %s, now(), 0) ON CONFLICT (color_id) DO NOTHING",
                            (int(c["color_id"]), c["color"], self.placeholder))
        self.conn.commit()
        print(f"  색상 {len(colors)}개를 시드 CSV 에서 넣었다(견본 이미지 = 자리 표시)")
        return [r[0] for r in self.rows("SELECT color_id FROM product.product_color ORDER BY color_id")]

    def resume_point(self, lo: int, hi: int):
        """[lo, hi] 범위에 이미 들어간 상품이 있으면 그 다음부터. 묶음은 한 트랜잭션이라 상품이 있으면 그 묶음은 온전하다."""
        last = self.scalar("SELECT max(product_id) FROM product.product WHERE product_id BETWEEN %s AND %s", (lo, hi))
        if last is None:
            return None
        sku = self.scalar("SELECT max(skuproduct_id) FROM product.product_sku WHERE product_id BETWEEN %s AND %s", (lo, hi))
        size = self.scalar("SELECT max(s.size_id) FROM product.product_size s WHERE s.skuproduct_id = %s", (sku,))
        link = self.scalar("SELECT max(proc_subcategory_id) FROM product.product_subcategory "
                           "WHERE product_id BETWEEN %s AND %s", (lo, hi))
        return last, sku, size, link

    def write(self, data: dict):
        with self.conn.cursor() as cur:
            for t in ("product", "product_subcategory", "product_sku", "product_size"):
                copy_rows(cur, t, pg_rows(t, data[t]))
        self.conn.commit()

    def finish(self):
        self.conn.autocommit = True
        with self.conn.cursor() as cur:
            # 신원 열의 시퀀스를 넣은 id 뒤로 — 안 그러면 관리자가 상품을 등록하다 언젠가 id 가 부딪힌다.
            for table, col in (("product", "product_id"), ("product_sku", "skuproduct_id"),
                               ("product_size", "size_id"), ("product_subcategory", "proc_subcategory_id"),
                               ("category", "category_id"), ("subcategory", "subcategory_id"),
                               ("product_color", "color_id")):
                cur.execute(f"SELECT pg_get_serial_sequence('product.{table}', '{col}')")
                seq = cur.fetchone()[0]
                if seq:
                    cur.execute(f"SELECT setval('{seq}', greatest((SELECT coalesce(max({col}), 1) FROM product.{table}), "
                                f"(SELECT last_value FROM {seq})))")
            # SKU 코드 번호 시퀀스도 — 앱이 만드는 코드가 생성기 코드와 겹치지 않게(그래도 앱은 존재 검사를 한다).
            cur.execute("SELECT setval('product.product_sku_seq', greatest((SELECT coalesce(max(skuproduct_id), 1) "
                        "FROM product.product_sku), (SELECT last_value FROM product.product_sku_seq)))")
            for t in ("product", "product_sku", "product_size", "product_subcategory"):
                cur.execute(f"ANALYZE product.{t}")
            print("  시퀀스 보정 · ANALYZE 완료")
            cur.execute("""
                SELECT c.relname, c.reltuples::bigint, pg_total_relation_size(c.oid), pg_relation_size(c.oid)
                  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'product' AND c.relkind = 'r'
                   AND c.relname IN ('product', 'product_sku', 'product_size', 'product_subcategory')
                 ORDER BY 3 DESC""")
            total = 0
            for name, rows, tot, heap in cur.fetchall():
                total += tot
                print(f"    {name:<20} {rows:>13,}행  표 {heap / 2**30:7.2f} GiB  인덱스 포함 {tot / 2**30:7.2f} GiB")
            print(f"    {'합계':<20} {'':>16}  {total / 2**30:28.2f} GiB")
        self.conn.close()

    def purge(self, lo: int, hi: int):
        """생성 범위만 지운다 — 자식부터. 큰 범위는 묶어서(긴 트랜잭션 하나로 WAL 이 폭증하지 않게)."""
        step = 200_000
        total = 0
        with self.conn.cursor() as cur:
            for a in range(lo, hi + 1, step):
                b = min(hi, a + step - 1)
                cur.execute("DELETE FROM product.product_size s USING product.product_sku k "
                            "WHERE s.skuproduct_id = k.skuproduct_id AND k.product_id BETWEEN %s AND %s", (a, b))
                cur.execute("DELETE FROM product.product_sku WHERE product_id BETWEEN %s AND %s", (a, b))
                cur.execute("DELETE FROM product.product_subcategory WHERE product_id BETWEEN %s AND %s", (a, b))
                cur.execute("DELETE FROM product.product WHERE product_id BETWEEN %s AND %s", (a, b))
                total += cur.rowcount
                self.conn.commit()
        print(f"  상품 {total:,}개와 그 SKU · 사이즈 · 연결을 지웠다. 공간은 VACUUM 뒤에 재사용된다"
              f"(디스크로 돌려받으려면 VACUUM FULL — 표를 잠근다).")
        self.conn.close()


# ── 출력: CSV(배치 잡용) ─────────────────────────────────────────────────────

class CsvSink:
    """배치 잡(BatchConfig)이 읽는 열 순서 그대로. sku_code 는 배치의 마지막 단계(DB 함수)가 채운다."""

    HEADERS = {
        "products": ["product_id", "name", "description", "brand", "slug", "category_id", "refund_policy",
                     "rating", "shipping", "created_at"],
        "product_skus": ["skuproduct_id", "sku", "images", "discount", "sold", "color_id", "product_id", "created_at"],
        "product_sizes": ["size_id", "size", "quantity", "price", "skuproduct_id"],
        "product_subcategories": ["id", "product_id", "subcategory_id"],
    }

    def __init__(self, outdir: Path):
        self.outdir = outdir
        outdir.mkdir(parents=True, exist_ok=True)
        self.files = {}
        self.writers = {}
        for name, header in self.HEADERS.items():
            fh = (outdir / f"{name}.csv").open("w", encoding="utf-8", newline="")
            self.files[name] = fh
            self.writers[name] = csv.writer(fh)
            self.writers[name].writerow(header)

    def prepare(self, plan: Plan, offset: int):
        ch, cats = read_csv("categories.csv")
        sh, subs = read_csv("subcategories.csv")
        colh, colors = read_csv("product_colors.csv")
        plan.build({c["category_name"]: int(c["category_id"]) for c in cats},
                   {(int(s["category_id"]), s["subcategory_name"]): int(s["subcategory_id"]) for s in subs},
                   max((int(c["category_id"]) for c in cats), default=0) + 1,
                   max((int(s["subcategory_id"]) for s in subs), default=0) + 1)
        plan.color_ids = [int(c["color_id"]) for c in colors] or [1]
        plan.images = classpath_images()
        today = date.today().isoformat()
        with (self.outdir / "categories.csv").open("w", encoding="utf-8", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(ch or ["category_id", "category_name", "slug", "created_at"])
            for c in cats:
                w.writerow([c["category_id"], c["category_name"], c["slug"], c["created_at"]])
            for c in plan.categories:
                if c["new"]:
                    w.writerow([c["id"], c["name"], c["slug"], today])
        with (self.outdir / "subcategories.csv").open("w", encoding="utf-8", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(sh or ["subcategory_id", "subcategory_name", "slug", "category_id", "created_at"])
            for s in subs:
                w.writerow([s["subcategory_id"], s["subcategory_name"], s["slug"], s["category_id"], s["created_at"]])
            for s in plan.subcats:
                if s["new"]:
                    w.writerow([s["id"], s["name"], s["slug"], s["category_id"], today])
        with (self.outdir / "product_colors.csv").open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=colh)
            w.writeheader()
            w.writerows(colors)
        # 배치 잡이 읽지만 시드가 비어 있는 파일들 — 헤더만
        for name, cols in (("product_details", ["pdetail_id", "name", "value", "product_id", "created_at"]),
                           ("product_qas", ["qa_id", "question", "answer", "product_id", "created_at"])):
            (self.outdir / f"{name}.csv").write_text(",".join(cols) + "\n", encoding="utf-8")

    def write(self, data: dict):
        w = self.writers
        for p in data["product"]:
            w["products"].writerow([p["product_id"], p["name"], p["description"], p["brand"], p["slug"],
                                    p["category_id"], p["refundpolicy"], p["rating"], p["shipping"], p["createdat"]])
        for sid, sku, imgs, disc, sold, color, pid, created in data["product_sku"]:
            w["product_skus"].writerow([sid, sku, ",".join(imgs), disc, sold, color, pid, created])
        for size_id, size, qty, price, sku_id, _code in data["product_size"]:
            w["product_sizes"].writerow([size_id, size, qty, price, sku_id])
        for link in data["product_subcategory"]:
            w["product_subcategories"].writerow(link)

    def finish(self):
        for fh in self.files.values():
            fh.close()
        for name in self.HEADERS:
            print(f"  {name}.csv  {(self.outdir / f'{name}.csv').stat().st_size / 2**20:,.1f} MiB")


# ── 용량 추정 ────────────────────────────────────────────────────────────────

# 로컬 PostgreSQL 16 에 dept 분류로 50만 개를 넣고 잰 값(docs/19 §5.1, 2026-10-01). 인덱스가 있는 채로 넣었다 —
# 운영과 같은 조건이라 인덱스 단편화까지 들어 있다. 바이트는 표 + 인덱스(14개)다.
MEASURED = {
    "rows_per_product": {"product_sku": 1.70, "product_size": 3.02, "product_subcategory": 1.0},
    "bytes_per_product": 2_108,
    "products_per_sec": 3_000,     # 4코어 x86 · synchronous_commit=off. 운영 노드 · 기본 설정이면 3~5배로 잡는다
}
# Elasticsearch 는 재지 않았다 — 문서(ProductDocument: 이름 · 설명 · 브랜드 다중 필드 + nested skus · subcategories)
# 원본 JSON 약 0.7KB 에 색인 오버헤드를 더한 추정 범위다.
ES_BYTES_PER_PRODUCT = (1_000, 1_600)


def estimate(n: int):
    r = MEASURED["rows_per_product"]
    gib = n * MEASURED["bytes_per_product"] / 2**30
    es_lo, es_hi = (n * b / 2**30 for b in ES_BYTES_PER_PRODUCT)
    hours = n / MEASURED["products_per_sec"] / 3600
    print(f">>> 추정 — 상품 {n:,}개 (dept 분류)")
    print(f"    product             {n:>14,}행")
    for t in ("product_sku", "product_size", "product_subcategory"):
        print(f"    {t:<19} {int(n * r[t]):>14,}행")
    print(f"    PostgreSQL 표 + 인덱스    약 {gib:,.1f} GiB  (실측 기반)")
    print(f"    적재 중 WAL 여유까지      디스크 약 {gib * 1.5:,.0f} GiB 가 비어 있어야 한다")
    print(f"    Elasticsearch 재색인 후   약 {es_lo:,.0f} ~ {es_hi:,.0f} GiB  (추정, 복제본 0)")
    print(f"    적재 시간                 약 {hours:,.1f}시간(4코어 실측) — 운영 노드면 {hours * 3:,.1f} ~ {hours * 5:,.1f}시간")
    return gib


# ── 진입점 ───────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--products", type=int, default=10000, help="생성할 상품 수 (기본 10000)")
    ap.add_argument("--taxonomy", choices=("dept", "seed"), default="dept",
                    help="dept = 내장 백화점식 분류(기본), seed = 시드 CSV 템플릿 증식(예전 동작)")
    ap.add_argument("--out", type=Path, help="CSV 출력 디렉터리(배치 잡용 — 소량만)")
    ap.add_argument("--copy", metavar="DSN", help="PostgreSQL DSN — COPY 로 직접 적재")
    ap.add_argument("--id-offset", type=int, default=1_000_000,
                    help="id 시작값. 기존 데이터와 충돌하지 않게 크게 잡는다 (기본 1000000)")
    ap.add_argument("--chunk", type=int, default=20_000, help="한 번에 만들고 커밋할 상품 수 (기본 20000)")
    ap.add_argument("--seed", type=int, default=42, help="난수 시드 — 재현 가능한 데이터셋")
    ap.add_argument("--resume", action="store_true", help="--copy: 이 범위에 이미 들어간 다음부터 잇는다")
    ap.add_argument("--purge", action="store_true", help="--copy: 이 범위(--id-offset, --products)의 생성 데이터를 지운다")
    ap.add_argument("--placeholder-image", metavar="URL",
                    help="--copy: 이미지 저장소가 없는 환경(데모)용 — 색상이 비어 있으면 시드 CSV 에서 넣고, "
                         "기존 상품 이미지가 없으면 모든 SKU 이미지를 이 주소로(예: /logo512.png — 프론트가 내보내는 파일)")
    ap.add_argument("--estimate", action="store_true", help="행 수 · 용량만 보여 주고 끝낸다")
    ap.add_argument("--yes", action="store_true", help="백만 개 이상 · --purge 를 묻지 않고 진행")
    args = ap.parse_args()

    if args.estimate:
        estimate(args.products)
        return
    if not args.out and not args.copy:
        ap.error("--out, --copy, --estimate 중 하나는 필요합니다")
    if (args.resume or args.purge or args.placeholder_image) and not args.copy:
        ap.error("--resume · --purge · --placeholder-image 는 --copy 와 함께")
    if args.placeholder_image:
        check_placeholder(args.placeholder_image)
    if args.out and args.products > 200_000:
        print("  ! CSV + 배치 잡은 JPA 로 한 행씩 넣고 SKU 마다 이미지를 올린다 — 20만 개를 넘으면 --copy 를 쓴다", file=sys.stderr)

    lo, hi = args.id_offset + 1, args.id_offset + args.products
    if args.purge:
        if not args.yes:
            sys.exit(f"상품 id {lo:,} ~ {hi:,} 와 그 SKU · 사이즈 · 연결을 지운다. 확인했으면 --yes 를 붙여 다시.")
        PgSink(args.copy).purge(lo, hi)
        return
    if args.products >= 1_000_000 and not args.yes:
        estimate(args.products)
        sys.exit("\n백만 개 이상이다. 위 용량을 확인했으면 --yes 를 붙여 다시 실행한다.")

    plan = Plan(args.taxonomy)
    sink = PgSink(args.copy, args.placeholder_image) if args.copy else CsvSink(args.out)
    print(f">>> 준비 ({args.taxonomy} 분류)")
    sink.prepare(plan, args.id_offset)
    if not plan.color_ids:
        sys.exit("ERROR: 색상이 없다")

    start, pid, sku, size, link = 0, lo, lo, lo, lo
    if args.copy:
        point = sink.resume_point(lo, hi)
        if point and not args.resume:
            sys.exit(f"ERROR: id {lo:,} ~ {hi:,} 에 이미 상품이 있다 — 이어서 넣으려면 --resume, 지우려면 --purge")
        if point:
            last, s, z, l = point
            start = last - lo + 1
            pid, sku, size, link = last + 1, (s or lo - 1) + 1, (z or lo - 1) + 1, (l or lo - 1) + 1
            print(f"  이어서 — 상품 {start:,}개가 이미 있다. id {pid:,} 부터")

    gen = Generator(plan, args.seed)
    t0 = time.time()
    rows = 0
    done = start
    print(f">>> 생성 · 적재: 상품 {args.products:,}개 (id {lo:,} ~ {hi:,}, 묶음 {args.chunk:,})")
    while done < args.products:
        n = min(args.chunk, args.products - done)
        data, (sku, size, link) = gen.chunk(done, n, pid, sku, size, link)
        sink.write(data)
        rows += sum(len(v) for v in data.values())
        done += n
        pid += n
        el = time.time() - t0
        rate = (done - start) / el if el else 0
        eta = (args.products - done) / rate if rate else 0
        print(f"\r  {done:>12,} / {args.products:,}  {rate:,.0f} 상품/초  남은 시간 약 {eta / 60:,.0f}분   ",
              end="", flush=True)
    print(f"\n  {rows:,}행 · {(time.time() - t0) / 60:,.1f}분")
    sink.finish()

    print("\n>>> 다음 단계")
    if args.out:
        print(f"    배치 잡은 클래스패스의 csv/ 를 읽는다 — catalog-service/src/main/resources/csv/ 를 {args.out} 의 파일로")
        print("    바꿔 빌드한다(시드 원본은 따로 보관). 배치의 마지막 단계가 사이즈별 SKU 코드를 채운다.")
    print("    검색이 Elasticsearch 모드(SEARCH_ENGINE=es)면 재색인 필요:  ./ops/03-enable-es-search.sh")
    print("    부하 측정:  ./ops/14-load-test.sh")
    print(f"    끝나면 지우기:  {sys.argv[0]} --products {args.products} --id-offset {args.id_offset} --copy … --purge --yes")


if __name__ == "__main__":
    main()
