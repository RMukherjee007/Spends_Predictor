/**
 * Integer minor-unit money formatting.
 * Amounts are always stored in minor units (e.g. paise for INR, cents for USD, yen for JPY).
 */

const DEFAULT_MINOR_UNITS: Record<string, number> = {
  INR: 2,
  USD: 2,
  EUR: 2,
  GBP: 2,
  JPY: 0,
  KRW: 0,
  KWD: 3,
  BHD: 3,
  OMR: 3,
}

export function formatMinorUnits(
  amountMinor: number,
  currency: string,
  locale = 'en-IN',
  minorUnits?: number,
): string {
  const code = currency.toUpperCase()
  const digits = minorUnits ?? DEFAULT_MINOR_UNITS[code] ?? 2
  const major = amountMinor / 10 ** digits
  return new Intl.NumberFormat(locale, {
    style: 'currency',
    currency: code,
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(major)
}
