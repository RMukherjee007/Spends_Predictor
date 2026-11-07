"""Two-stage Merchant Entity Resolution & Hybrid Transaction Categorization Engine.

Stage 1: Clean noisy UPI / POS / NEFT / ACH narration strings into a canonical merchant name.
Stage 2: Categorize using:
  (a) User's personal active-learning rules (`CategoryRule`) -> source="user"
  (b) High-precision merchant/keyword rules -> source="rule"
  (c) Global TF-IDF + LogisticRegression classifier -> source="ml"
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from functools import lru_cache

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import f1_score
from sklearn.model_selection import train_test_split
from sklearn.pipeline import FeatureUnion, Pipeline

CATEGORIES: tuple[str, ...] = (
    "dining",
    "groceries",
    "rent",
    "utilities",
    "transport",
    "shopping",
    "subscriptions",
    "healthcare",
    "travel",
    "entertainment",
    "investments",
    "salary",
    "other",
)

_NOISE_PREFIXES = re.compile(
    r"^(?:UPI(?:/CR|/DR)?|POS|NEFT|IMPS|ACH|AUTOPAY|BIL|INTL|ECOM)"
    r"[\s/\-:0-9A-Z]*?(?=[A-Za-z]{3,})",
    re.IGNORECASE,
)
_TRAILING_NOISE = re.compile(
    r"(?:/[A-Z0-9._@-]+|\b(?:REF|TXN|UTR|ID|NO|IN|BANGALORE|MUMBAI|DELHI|HYDERABAD)\b.*|\d{4,})$",
    re.IGNORECASE,
)

# Canonical entity resolution aliases
_CANONICAL_MERCHANTS: tuple[tuple[re.Pattern[str], str, str], ...] = (
    (
        re.compile(
            r"swiggy\s*instamart|blinkit|zepto|bigbasket|dmart|ratnadeep|nature.?s\s*basket", re.I
        ),
        "Swiggy Instamart / Blinkit",
        "groceries",
    ),
    (
        re.compile(
            r"swiggy|zomato|mcdonald|starbucks|third\s*wave|domino|kfc|barbeque|truffles|blue\s*tokai",
            re.I,
        ),
        "Swiggy / Zomato Dining",
        "dining",
    ),
    (
        re.compile(
            r"uber|ola\s*cabs|rapido|namma\s*yatri|bmrc|metro|indian\s*oil|hpcl|bpcl|shell\s*petrol",
            re.I,
        ),
        "Uber / Commute",
        "transport",
    ),
    (
        re.compile(
            r"netflix|spotify|youtube\s*premium|apple\.com/bill|prime\s*video|hotstar|openai|chatgpt|claude|icloud",
            re.I,
        ),
        "Digital Subscription",
        "subscriptions",
    ),
    (re.compile(r"cult\.?fit|gold.?s\s*gym", re.I), "Cult.fit Gym", "subscriptions"),
    (
        re.compile(
            r"airtel|jio\s*fiber|act\s*fibernet|bescom|tata\s*power|bwssb|indane\s*gas|mahanagar\s*gas",
            re.I,
        ),
        "Utilities & Broadband",
        "utilities",
    ),
    (
        re.compile(r"nobroker\s*rent|house\s*rent|landlord\s*rent|nestaway|stanza\s*living", re.I),
        "Monthly House Rent",
        "rent",
    ),
    (
        re.compile(
            r"amzn|amazon|flipkart|myntra|nykaa|ajio|uniqlo|ikea|decathlon|croma|apple\s*store",
            re.I,
        ),
        "Amazon / Retail",
        "shopping",
    ),
    (
        re.compile(
            r"apollo\s*pharmacy|1mg|pharmeasy|practo|manipal\s*hosp|fortis|max\s*health", re.I
        ),
        "Apollo / Healthcare",
        "healthcare",
    ),
    (
        re.compile(
            r"indigo|air\s*india|makemytrip|irctc|airbnb|booking\.com|taj\s*hotels|marriott", re.I
        ),
        "Flights & Hotels",
        "travel",
    ),
    (
        re.compile(r"bookmyshow|pvr\s*inox|steam\s*games|playstation", re.I),
        "BookMyShow / PVR",
        "entertainment",
    ),
    (
        re.compile(
            r"zerodha|groww|kuvera|coin\s*by\s*zerodha|ppf\s*contribution|nps\s*trust", re.I
        ),
        "Zerodha / Mutual Funds",
        "investments",
    ),
    (
        re.compile(
            r"salary\s*credit|payroll|stipend|acme\s*corp\s*payroll|techcorp\s*india\s*salary", re.I
        ),
        "Employer Payroll",
        "salary",
    ),
)

# Labeled seed dataset for the global TF-IDF + LogisticRegression classifier
_SEED_CORPUS: tuple[tuple[str, str], ...] = (
    ("UPI/301928/SWIGGY FOOD ORDER/BLR", "dining"),
    ("UPI/982341/ZOMATO LIMITED/DELHI", "dining"),
    ("POS STARBUCKS COFFEE MUMBAI", "dining"),
    ("UPI/192831/THIRD WAVE COFFEE ROASTERS", "dining"),
    ("POS MCDONALDS FAMILY RESTAURANT", "dining"),
    ("UPI/228192/TRUFFLES KORAMANGALA", "dining"),
    ("UPI/554123/BLUE TOKAI COFFEE", "dining"),
    ("POS DOMINOS PIZZA INDIA", "dining"),
    ("UPI/112233/SWIGGY INSTAMART GROCERY", "groceries"),
    ("UPI/445566/BLINKIT GROCERS PVT LTD", "groceries"),
    ("UPI/778899/ZEPTO SUPERMARKET", "groceries"),
    ("POS DMART AVENUE SUPERMARTS", "groceries"),
    ("UPI/334455/BIGBASKET SUPERMARKET", "groceries"),
    ("POS RATNADEEP RETAIL GROCERY", "groceries"),
    ("NEFT/RENT/HOUSE RENT LANDLORD", "rent"),
    ("UPI/998877/NOBROKER RENT PAYMENT", "rent"),
    ("IMPS/MONTHLY APARTMENT RENT", "rent"),
    ("ACH/STANZA LIVING HOSTEL RENT", "rent"),
    ("UPI/665544/BESCOM ELECTRICITY BILL", "utilities"),
    ("UPI/223344/AIRTEL FIBER BROADBAND", "utilities"),
    ("UPI/556677/JIO FIBER POSTPAID BILL", "utilities"),
    ("BILLPAY/TATA POWER ELECTRICITY", "utilities"),
    ("UPI/887766/ACT FIBERNET INTERNET", "utilities"),
    ("UPI/119922/BWSSB WATER BILL", "utilities"),
    ("UPI/443322/UBER INDIA SYSTEMS TRIP", "transport"),
    ("UPI/776655/OLA CABS RIDE", "transport"),
    ("UPI/332211/NAMMA YATRI AUTO", "transport"),
    ("POS INDIAN OIL PETROL PUMP", "transport"),
    ("POS SHELL FUEL STATION", "transport"),
    ("UPI/BMRC METRO RAIL RECHARGE", "transport"),
    ("POS AMZN MKTP IN SHOPPING", "shopping"),
    ("UPI/FLIPKART INTERNET PVT LTD", "shopping"),
    ("POS MYNTRA DESIGNS APPAREL", "shopping"),
    ("POS IKEA INDIA FURNISHING", "shopping"),
    ("POS DECATHLON SPORTS INDIA", "shopping"),
    ("POS CROMA ELECTRONICS STORE", "shopping"),
    ("ACH/NETFLIX.COM SUBSCRIPTION", "subscriptions"),
    ("ACH/SPOTIFY INDIA PREMIUM", "subscriptions"),
    ("POS APPLE.COM/BILL ICLOUD", "subscriptions"),
    ("POS OPENAI CHATGPT PLUS SUB", "subscriptions"),
    ("UPI/CULTFIT HEALTHCARE MEMBERSHIP", "subscriptions"),
    ("ACH/YOUTUBE PREMIUM GOOGLE", "subscriptions"),
    ("POS APOLLO PHARMACY MEDICINES", "healthcare"),
    ("UPI/TATA 1MG LABS DIAGNOSTICS", "healthcare"),
    ("POS MANIPAL HOSPITAL CONSULTATION", "healthcare"),
    ("UPI/PRACTO HEALTH CLINIC", "healthcare"),
    ("POS INDIGO AIRLINES FLIGHT TICKET", "travel"),
    ("UPI/IRCTC RAIL TICKET BOOKING", "travel"),
    ("POS MAKEMYTRIP HOTEL BOOKING", "travel"),
    ("POS AIRBNB STAYS RESERVATION", "travel"),
    ("UPI/BOOKMYSHOW MOVIE TICKETS", "entertainment"),
    ("POS PVR INOX CINEMAS", "entertainment"),
    ("POS STEAM GAMES PURCHASE", "entertainment"),
    ("ACH/ZERODHA BROKING SIP", "investments"),
    ("UPI/GROWW MUTUAL FUND SIP", "investments"),
    ("NEFT/COIN BY ZERODHA INVESTMENT", "investments"),
    ("NEFT/TECHCORP INDIA SALARY CREDIT", "salary"),
    ("ACH/ACME CORP PAYROLL CREDIT", "salary"),
    ("NEFT/MONTHLY SALARY DISBURSEMENT", "salary"),
)


@dataclass(frozen=True, slots=True)
class CategorizationResult:
    merchant_name: str
    category: str
    source: str  # "user" | "rule" | "ml"
    confidence: float


@dataclass(frozen=True, slots=True)
class ClassifierBundle:
    pipeline: Pipeline
    test_f1_macro: float
    train_size: int
    test_size: int


_BRAND_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"swiggy\s*instamart", re.I), "Swiggy Instamart"),
    (re.compile(r"\bblinkit\b", re.I), "Blinkit"),
    (re.compile(r"\bzepto\b", re.I), "Zepto"),
    (re.compile(r"\bbigbasket\b", re.I), "BigBasket"),
    (re.compile(r"\bdmart\b", re.I), "DMart"),
    (re.compile(r"\bratnadeep\b", re.I), "Ratnadeep"),
    (re.compile(r"\bswiggy\b", re.I), "Swiggy"),
    (re.compile(r"\bzomato\b", re.I), "Zomato"),
    (re.compile(r"\bstarbucks\b", re.I), "Starbucks"),
    (re.compile(r"third\s*wave", re.I), "Third Wave Coffee"),
    (re.compile(r"blue\s*tokai", re.I), "Blue Tokai"),
    (re.compile(r"\bdomino", re.I), "Domino's"),
    (re.compile(r"\bmcdonald", re.I), "McDonald's"),
    (re.compile(r"\btruffles\b", re.I), "Truffles"),
    (re.compile(r"\buber\b", re.I), "Uber"),
    (re.compile(r"ola\s*cabs|\bola\b", re.I), "Ola Cabs"),
    (re.compile(r"namma\s*yatri", re.I), "Namma Yatri"),
    (re.compile(r"indian\s*oil", re.I), "Indian Oil"),
    (re.compile(r"\bshell\b", re.I), "Shell"),
    (re.compile(r"\bbmrc\b|\bmetro\b", re.I), "BMRC Metro"),
    (re.compile(r"\bnetflix", re.I), "Netflix"),
    (re.compile(r"\bspotify\b", re.I), "Spotify"),
    (re.compile(r"youtube\s*premium", re.I), "YouTube Premium"),
    (re.compile(r"apple\.com/bill|\bicloud\b", re.I), "Apple Services"),
    (re.compile(r"openai|chatgpt", re.I), "OpenAI ChatGPT"),
    (re.compile(r"cult\.?fit", re.I), "Cult.fit"),
    (re.compile(r"\bairtel\b", re.I), "Airtel Fiber"),
    (re.compile(r"jio\s*fiber", re.I), "Jio Fiber"),
    (re.compile(r"act\s*fibernet", re.I), "ACT Fibernet"),
    (re.compile(r"\bbescom\b", re.I), "BESCOM Electricity"),
    (re.compile(r"tata\s*power", re.I), "Tata Power"),
    (re.compile(r"\bbwssb\b", re.I), "BWSSB Water"),
    (re.compile(r"\bnobroker\b", re.I), "NoBroker Rent"),
    (re.compile(r"stanza\s*living", re.I), "Stanza Living"),
    (re.compile(r"house\s*rent|apartment\s*rent|landlord\s*rent", re.I), "House Rent"),
    (re.compile(r"\bamzn\b|\bamazon\b", re.I), "Amazon"),
    (re.compile(r"\bflipkart\b", re.I), "Flipkart"),
    (re.compile(r"\bmyntra\b", re.I), "Myntra"),
    (re.compile(r"\bikea\b", re.I), "IKEA"),
    (re.compile(r"\bdecathlon\b", re.I), "Decathlon"),
    (re.compile(r"\bcroma\b", re.I), "Croma Electronics"),
    (re.compile(r"apple\s*store", re.I), "Apple Store"),
    (re.compile(r"apollo\s*pharmacy", re.I), "Apollo Pharmacy"),
    (re.compile(r"\b1mg\b", re.I), "Tata 1mg"),
    (re.compile(r"manipal\s*hosp", re.I), "Manipal Hospital"),
    (re.compile(r"\bpracto\b", re.I), "Practo"),
    (re.compile(r"\bindigo\b", re.I), "IndiGo Airlines"),
    (re.compile(r"\birctc\b", re.I), "IRCTC Railways"),
    (re.compile(r"\bmakemytrip\b", re.I), "MakeMyTrip"),
    (re.compile(r"\bairbnb\b", re.I), "Airbnb"),
    (re.compile(r"\bbookmyshow\b", re.I), "BookMyShow"),
    (re.compile(r"pvr\s*inox", re.I), "PVR Inox"),
    (re.compile(r"\bsteam\b", re.I), "Steam Games"),
    (re.compile(r"\bzerodha\b", re.I), "Zerodha SIP"),
    (re.compile(r"\bgroww\b", re.I), "Groww Mutual Fund"),
    (re.compile(r"\btechcorp\b", re.I), "TechCorp Salary"),
)


def clean_merchant_name(raw_description: str) -> str:
    """Clean noisy bank/UPI transaction narrations into a human-readable merchant name."""
    cleaned = raw_description.strip()
    for pattern, canonical, _ in _CANONICAL_MERCHANTS:
        if pattern.search(cleaned):
            for brand_pat, brand_label in _BRAND_PATTERNS:
                if brand_pat.search(cleaned):
                    return brand_label
            return canonical

    stripped = _NOISE_PREFIXES.sub("", cleaned)
    stripped = _TRAILING_NOISE.sub("", stripped)
    stripped = re.sub(r"[/\-_]+", " ", stripped)
    stripped = re.sub(r"\s+", " ", stripped).strip()
    if not stripped:
        return cleaned[:48] or "Unknown Merchant"
    return stripped.title()[:64]


@lru_cache(maxsize=1)
def get_global_classifier() -> ClassifierBundle:
    """Train and evaluate the global TF-IDF + LogisticRegression transaction classifier."""
    texts = [row[0] for row in _SEED_CORPUS]
    labels = [row[1] for row in _SEED_CORPUS]

    x_train, x_test, y_train, y_test = train_test_split(
        texts, labels, test_size=0.25, random_state=42, stratify=labels
    )

    def _make_pipeline() -> Pipeline:
        features = FeatureUnion(
            [
                (
                    "word",
                    TfidfVectorizer(ngram_range=(1, 2), lowercase=True, sublinear_tf=True),
                ),
                (
                    "char",
                    TfidfVectorizer(
                        analyzer="char_wb", ngram_range=(3, 5), lowercase=True, sublinear_tf=True
                    ),
                ),
            ]
        )
        clf = LogisticRegression(C=4.0, max_iter=500, random_state=42)
        return Pipeline([("features", features), ("clf", clf)])

    eval_pipe = _make_pipeline()
    eval_pipe.fit(x_train, y_train)
    preds = eval_pipe.predict(x_test)
    f1 = float(f1_score(y_test, preds, average="macro"))

    full_pipe = _make_pipeline()
    full_pipe.fit(texts, labels)
    return ClassifierBundle(
        pipeline=full_pipe,
        test_f1_macro=round(f1, 4),
        train_size=len(x_train),
        test_size=len(x_test),
    )


def categorize_transaction(
    raw_description: str,
    user_rules: dict[str, str] | None = None,
) -> CategorizationResult:
    """Categorize a transaction description using user overrides, deterministic rules, and ML."""
    merchant = clean_merchant_name(raw_description)

    # 1. Per-user active-learning overrides
    if user_rules:
        lower_desc = raw_description.lower()
        lower_merch = merchant.lower()
        for pattern, cat in user_rules.items():
            pat = pattern.lower()
            if pat in lower_merch or pat in lower_desc:
                return CategorizationResult(
                    merchant_name=merchant, category=cat, source="user", confidence=0.99
                )

    # 2. High-precision deterministic lexicon
    for pat_regex, _, cat in _CANONICAL_MERCHANTS:
        if pat_regex.search(raw_description):
            return CategorizationResult(
                merchant_name=merchant, category=cat, source="rule", confidence=0.94
            )

    # 3. Global TF-IDF + LogisticRegression classifier
    bundle = get_global_classifier()
    probs = bundle.pipeline.predict_proba([raw_description])[0]
    classes = list(bundle.pipeline.classes_)
    best_idx = int(np.argmax(probs))
    confidence = float(probs[best_idx])
    predicted_cat = str(classes[best_idx]) if confidence >= 0.28 else "other"
    return CategorizationResult(
        merchant_name=merchant,
        category=predicted_cat,
        source="ml",
        confidence=round(confidence, 3),
    )
