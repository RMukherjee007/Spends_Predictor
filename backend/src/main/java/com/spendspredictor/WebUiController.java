package com.spendspredictor;

import com.spendspredictor.ApiModels.AppException;
import com.spendspredictor.ApiModels.BankAccountRead;
import com.spendspredictor.ApiModels.CsvImportResponse;
import com.spendspredictor.ApiModels.CurrencyInfo;
import com.spendspredictor.ApiModels.TransactionRead;
import com.spendspredictor.ApiModels.UserRead;
import com.spendspredictor.FinanceService.RotatedSession;
import com.spendspredictor.SecurityService.AccessTokenClaims;
import com.spendspredictor.SecurityService.IssuedTokens;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.text.NumberFormat;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class WebUiController {

    private static final List<String> ALL_CATEGORIES = List.of(
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
        "other"
    );

    private static final List<String> COMMON_TIMEZONES = List.of(
        "Asia/Kolkata",
        "UTC",
        "America/New_York",
        "America/Los_Angeles",
        "Europe/London",
        "Europe/Berlin",
        "Asia/Singapore",
        "Asia/Tokyo",
        "Australia/Sydney"
    );

    private final FinanceService finance;
    private final SecurityService security;

    public WebUiController(FinanceService finance, SecurityService security) {
        this.finance = finance;
        this.security = security;
    }

    /** Formats integer minor units according to ISO-4217 currency exponents (0, 2, or 3 decimals). */
    public static final class MoneyFormatter {
        private final String baseCurrency;
        private final Locale locale;

        public MoneyFormatter(String baseCurrency, String localeTag) {
            this.baseCurrency = baseCurrency != null ? baseCurrency.toUpperCase() : "INR";
            this.locale = localeTag != null && !localeTag.isBlank()
                ? Locale.forLanguageTag(localeTag)
                : Locale.forLanguageTag("en-IN");
        }

        public String format(Object minorObj) {
            return formatInCurrency(minorObj, baseCurrency);
        }

        public String formatInCurrency(Object minorObj, String currencyCode) {
            long minor = minorObj instanceof Number n ? n.longValue() : 0L;
            String code = currencyCode != null ? currencyCode.toUpperCase() : baseCurrency;
            int digits = ApiModels.CURRENCY_MINOR_UNITS.getOrDefault(code, 2);
            double major = minor / Math.pow(10, digits);

            NumberFormat nf = NumberFormat.getCurrencyInstance(locale);
            try {
                nf.setCurrency(Currency.getInstance(code));
            } catch (IllegalArgumentException ignored) {
                // Keep default currency symbol if unknown ISO code
            }
            nf.setMinimumFractionDigits(digits);
            nf.setMaximumFractionDigits(digits);
            return nf.format(major);
        }
    }

    private UserRead resolveSessionUser(HttpServletRequest request, HttpServletResponse response) {
        String accessCookie = security.getCookieValue(request, SecurityService.ACCESS_COOKIE);
        if (accessCookie != null && !accessCookie.isBlank()) {
            try {
                AccessTokenClaims claims = security.decodeAccessToken(accessCookie);
                if (!security.isJtiRevoked(claims.jti())) {
                    return finance.getUserById(claims.userId());
                }
            } catch (Exception ignored) {
                // Fall through to refresh cookie check
            }
        }
        String refreshCookie = security.getCookieValue(request, SecurityService.REFRESH_COOKIE);
        if (refreshCookie != null && !refreshCookie.isBlank()) {
            try {
                RotatedSession rotated = finance.rotateRefreshToken(refreshCookie, request.getHeader("User-Agent"));
                security.setAuthCookies(response, rotated.tokens());
                return rotated.user();
            } catch (Exception ignored) {
                security.clearAuthCookies(response);
            }
        }
        return null;
    }

    private boolean isHtmx(HttpServletRequest request) {
        return "true".equalsIgnoreCase(request.getHeader("HX-Request"));
    }

    private void populateCommonModel(Model model, UserRead user) {
        model.addAttribute("user", user);
        model.addAttribute("currency", user.baseCurrency());
        model.addAttribute("fmt", new MoneyFormatter(user.baseCurrency(), user.locale()));
    }

    @GetMapping("/login")
    public String loginPage(HttpServletRequest request, HttpServletResponse response) {
        UserRead user = resolveSessionUser(request, response);
        if (user != null) {
            return "redirect:/";
        }
        return "login";
    }

    @PostMapping("/ui/login")
    public String handleLogin(
        @RequestParam String email,
        @RequestParam String password,
        @RequestParam(name = "full_name", required = false) String fullName,
        HttpServletRequest request,
        HttpServletResponse response,
        Model model
    ) {
        try {
            String ip = security.resolveClientIp(request);
            security.checkRateLimit("auth", ip, 20, 60.0);
            UserRead user = finance.authenticateOrRegisterUser(email, password, fullName);
            IssuedTokens tokens = finance.issueTokens(user.id(), "web", request.getHeader("User-Agent"), null);
            security.setAuthCookies(response, tokens);
            return "redirect:/";
        } catch (AppException ex) {
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("email", email);
            model.addAttribute("fullName", fullName);
            return "login";
        }
    }

    @PostMapping("/ui/logout")
    public String handleLogout(HttpServletRequest request, HttpServletResponse response) {
        String cookieRefresh = security.getCookieValue(request, SecurityService.REFRESH_COOKIE);
        if (cookieRefresh != null && !cookieRefresh.isBlank()) {
            finance.revokeRefreshToken(cookieRefresh);
        }
        String cookieAccess = security.getCookieValue(request, SecurityService.ACCESS_COOKIE);
        if (cookieAccess != null && !cookieAccess.isBlank()) {
            try {
                AccessTokenClaims claims = security.decodeAccessToken(cookieAccess);
                security.revokeAccessJti(claims.jti());
            } catch (Exception ignored) {
                // Ignore expired token on logout
            }
        }
        security.clearAuthCookies(response);
        return "redirect:/login";
    }

    @GetMapping("/")
    @SuppressWarnings("unchecked")
    public String dashboardPage(HttpServletRequest request, HttpServletResponse response, Model model) {
        UserRead user = resolveSessionUser(request, response);
        if (user == null) {
            return "redirect:/login";
        }
        populateCommonModel(model, user);

        List<BankAccountRead> accounts = finance.listAccounts(user);
        Map<String, Object> insights = finance.getUserMlInsights(user);
        Map<String, Object> runway = (Map<String, Object>) insights.get("runway");
        Map<String, Object> javaAudit = (Map<String, Object>) insights.get("java_audit");
        List<Map<String, Object>> recurringBills = (List<Map<String, Object>>) insights.getOrDefault("recurring_bills", List.of());
        List<Map<String, Object>> anomalies = (List<Map<String, Object>>) insights.getOrDefault("anomalies", List.of());

        long verifiedDebits = javaAudit != null ? ((Number) javaAudit.getOrDefault("verified_debit_count", 0)).longValue() : 0L;
        long verifiedCredits = javaAudit != null ? ((Number) javaAudit.getOrDefault("verified_credit_count", 0)).longValue() : 0L;
        boolean hasLedgerData = !accounts.isEmpty() || (verifiedDebits + verifiedCredits > 0);

        List<Map<String, Object>> hikeAlerts = recurringBills.stream()
            .filter(b -> Boolean.TRUE.equals(b.get("has_price_hike")))
            .toList();
        List<Map<String, Object>> highSeverityAnomalies = anomalies.stream()
            .filter(a -> "high".equalsIgnoreCase(String.valueOf(a.get("combined_severity"))))
            .toList();

        model.addAttribute("accounts", accounts);
        model.addAttribute("insights", insights);
        model.addAttribute("runway", runway);
        model.addAttribute("javaAudit", javaAudit);
        model.addAttribute("hasLedgerData", hasLedgerData);
        model.addAttribute("projectedMonthSpend", insights.getOrDefault("forecast_p50_minor", 0L));
        model.addAttribute("forecastP10", insights.getOrDefault("forecast_p10_minor", 0L));
        model.addAttribute("forecastP90", insights.getOrDefault("forecast_p90_minor", 0L));
        model.addAttribute("spentSoFar", insights.getOrDefault("spent_this_month_minor", 0L));
        model.addAttribute("dailyVelocity", insights.getOrDefault("daily_velocity_minor", 0L));
        model.addAttribute("upcomingRecurring", insights.getOrDefault("remaining_recurring_minor", 0L));
        model.addAttribute("recurringBills", recurringBills);
        model.addAttribute("hikeAlerts", hikeAlerts);
        model.addAttribute("highSeverityAnomalies", highSeverityAnomalies);

        return "dashboard";
    }

    @GetMapping("/intelligence")
    @SuppressWarnings("unchecked")
    public String intelligencePage(
        @RequestParam(defaultValue = "forecast") String tab,
        HttpServletRequest request,
        HttpServletResponse response,
        Model model
    ) {
        UserRead user = resolveSessionUser(request, response);
        if (user == null) {
            return "redirect:/login";
        }
        populateCommonModel(model, user);

        Set<String> validTabs = Set.of("forecast", "models", "runway", "recurring", "anomalies", "personas");
        String activeTab = validTabs.contains(tab) ? tab : "forecast";

        Map<String, Object> insights = finance.getUserMlInsights(user);
        Map<String, Object> runway = (Map<String, Object>) insights.get("runway");
        Map<String, Object> javaAudit = (Map<String, Object>) insights.get("java_audit");
        List<Map<String, Object>> categoryForecasts = (List<Map<String, Object>>) insights.getOrDefault("category_forecasts", List.of());

        long verifiedDebits = javaAudit != null ? ((Number) javaAudit.getOrDefault("verified_debit_count", 0)).longValue() : 0L;
        long verifiedCredits = javaAudit != null ? ((Number) javaAudit.getOrDefault("verified_credit_count", 0)).longValue() : 0L;
        boolean hasLedgerData = !categoryForecasts.isEmpty() || (verifiedDebits + verifiedCredits > 0);

        model.addAttribute("activeTab", activeTab);
        model.addAttribute("hasLedgerData", hasLedgerData);
        model.addAttribute("insights", insights);
        model.addAttribute("runway", runway);
        model.addAttribute("javaAudit", javaAudit);
        model.addAttribute("categoryForecasts", categoryForecasts);
        model.addAttribute("modelComparisons", insights.getOrDefault("model_comparisons", List.of()));
        model.addAttribute("backtestSeries", insights.getOrDefault("backtest_series", List.of()));
        model.addAttribute("trajectory", runway != null ? runway.getOrDefault("trajectory", List.of()) : List.of());
        model.addAttribute("recurringBills", insights.getOrDefault("recurring_bills", List.of()));
        model.addAttribute("anomalies", insights.getOrDefault("anomalies", List.of()));
        model.addAttribute("lifestyleCreep", insights.getOrDefault("lifestyle_creep", List.of()));
        model.addAttribute("clusters", insights.getOrDefault("clusters", List.of()));
        model.addAttribute("shapAttributions", insights.getOrDefault("shap_attributions", List.of()));
        model.addAttribute("savingsRecommendations", insights.getOrDefault("savings_recommendations", List.of()));

        if (isHtmx(request)) {
            return "intelligence :: tabContent";
        }
        return "intelligence";
    }

    @GetMapping("/transactions")
    public String transactionsPage(
        @RequestParam(name = "q", defaultValue = "") String searchQuery,
        @RequestParam(defaultValue = "all") String category,
        HttpServletRequest request,
        HttpServletResponse response,
        Model model
    ) {
        UserRead user = resolveSessionUser(request, response);
        if (user == null) {
            return "redirect:/login";
        }
        populateCommonModel(model, user);

        String catFilter = ("all".equalsIgnoreCase(category) || category.isBlank()) ? null : category;
        List<TransactionRead> allTxns = finance.listTransactions(user, 200, 0, catFilter, null);
        String qLower = searchQuery.trim().toLowerCase();
        List<TransactionRead> filtered = qLower.isEmpty()
            ? allTxns
            : allTxns.stream()
                .filter(t -> t.merchantName().toLowerCase().contains(qLower)
                    || t.category().toLowerCase().contains(qLower)
                    || t.direction().toLowerCase().contains(qLower))
                .toList();

        model.addAttribute("transactions", filtered);
        model.addAttribute("accounts", finance.listAccounts(user));
        model.addAttribute("allCategories", ALL_CATEGORIES);
        model.addAttribute("searchQuery", searchQuery);
        model.addAttribute("selectedCategory", category);

        if (isHtmx(request)) {
            return "transactions :: tableContainer";
        }
        return "transactions";
    }

    @PostMapping("/ui/transactions/{transactionId}/category")
    public String updateTransactionCategoryUi(
        @PathVariable UUID transactionId,
        @RequestParam String category,
        HttpServletRequest request,
        HttpServletResponse response,
        Model model,
        RedirectAttributes redirectAttributes
    ) {
        UserRead user = resolveSessionUser(request, response);
        if (user == null) {
            return "redirect:/login";
        }
        finance.updateTransactionCategory(user, transactionId, category, true);
        String msg = "Category updated to \"" + category + "\". Active-learning rule saved!";

        if (isHtmx(request)) {
            populateCommonModel(model, user);
            model.addAttribute("transactions", finance.listTransactions(user, 200, 0, null, null));
            model.addAttribute("allCategories", ALL_CATEGORIES);
            model.addAttribute("statusMessage", msg);
            model.addAttribute("statusError", false);
            return "transactions :: tableContainer";
        }

        redirectAttributes.addFlashAttribute("statusMessage", msg);
        redirectAttributes.addFlashAttribute("statusError", false);
        return "redirect:/transactions";
    }

    @PostMapping("/ui/transactions/import-csv")
    public String importCsvUi(
        @RequestParam(name = "account_id", required = false) String accountIdStr,
        @RequestParam(name = "csv_content") String csvContent,
        HttpServletRequest request,
        HttpServletResponse response,
        RedirectAttributes redirectAttributes
    ) {
        UserRead user = resolveSessionUser(request, response);
        if (user == null) {
            return "redirect:/login";
        }
        try {
            UUID accountId = (accountIdStr != null && !accountIdStr.isBlank()) ? UUID.fromString(accountIdStr) : null;
            CsvImportResponse res = finance.importCsvStatement(user, accountId, csvContent);
            redirectAttributes.addFlashAttribute(
                "statusMessage",
                "Successfully imported " + res.importedCount() + " new transactions (" + res.skippedCount() + " duplicates skipped)."
            );
            redirectAttributes.addFlashAttribute("statusError", false);
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("statusMessage", "Failed to import CSV statement: " + ex.getMessage());
            redirectAttributes.addFlashAttribute("statusError", true);
        }
        return "redirect:/transactions";
    }

    @GetMapping("/accounts")
    public String accountsPage(HttpServletRequest request, HttpServletResponse response, Model model) {
        UserRead user = resolveSessionUser(request, response);
        if (user == null) {
            return "redirect:/login";
        }
        populateCommonModel(model, user);

        List<BankAccountRead> accounts = finance.listAccounts(user);
        List<BankAccountRead> liquidAccounts = accounts.stream()
            .filter(a -> Set.of("checking", "savings", "wallet").contains(a.accountType().toLowerCase()))
            .toList();
        List<BankAccountRead> creditAccounts = accounts.stream()
            .filter(a -> Set.of("credit", "credit_card").contains(a.accountType().toLowerCase()))
            .toList();

        long totalLiquidBaseMinor = liquidAccounts.stream()
            .mapToLong(BankAccountRead::balanceBaseMinor)
            .sum();
        long totalCreditBaseMinor = creditAccounts.stream()
            .mapToLong(a -> Math.abs(a.balanceBaseMinor()))
            .sum();

        model.addAttribute("accounts", accounts);
        model.addAttribute("liquidCount", liquidAccounts.size());
        model.addAttribute("creditCount", creditAccounts.size());
        model.addAttribute("totalLiquidBaseMinor", totalLiquidBaseMinor);
        model.addAttribute("totalCreditBaseMinor", totalCreditBaseMinor);

        return "accounts";
    }

    @PostMapping("/ui/accounts/sync")
    public String syncMockAccountsUi(
        @RequestParam(defaultValue = "/accounts") String redirect,
        HttpServletRequest request,
        HttpServletResponse response,
        RedirectAttributes redirectAttributes
    ) {
        UserRead user = resolveSessionUser(request, response);
        if (user == null) {
            return "redirect:/login";
        }
        List<BankAccountRead> synced = finance.syncMockBankData(user);
        redirectAttributes.addFlashAttribute(
            "statusMessage",
            "Successfully synced " + synced.size() + " accounts with 6 months of historical transactions and ML telemetry."
        );
        String target = (redirect != null && redirect.startsWith("/")) ? redirect : "/accounts";
        return "redirect:" + target;
    }

    @GetMapping("/settings")
    public String settingsPage(HttpServletRequest request, HttpServletResponse response, Model model) {
        UserRead user = resolveSessionUser(request, response);
        if (user == null) {
            return "redirect:/login";
        }
        populateCommonModel(model, user);

        List<CurrencyInfo> currencies = ApiModels.CURRENCY_MINOR_UNITS.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
            .map(e -> new CurrencyInfo(e.getKey(), e.getValue()))
            .toList();

        model.addAttribute("currencies", currencies);
        model.addAttribute("timezones", COMMON_TIMEZONES);
        return "settings";
    }

    @PostMapping("/ui/settings")
    public String updateSettingsUi(
        @RequestParam(name = "full_name", required = false) String fullName,
        @RequestParam(name = "base_currency") String baseCurrency,
        @RequestParam(name = "timezone") String timezone,
        HttpServletRequest request,
        HttpServletResponse response,
        RedirectAttributes redirectAttributes
    ) {
        UserRead user = resolveSessionUser(request, response);
        if (user == null) {
            return "redirect:/login";
        }
        try {
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("full_name", (fullName != null && !fullName.trim().isEmpty()) ? fullName.trim() : null);
            patch.put("base_currency", baseCurrency);
            patch.put("timezone", timezone);
            finance.updateUser(user.id(), patch);
            redirectAttributes.addFlashAttribute("statusMessage", "Preferences saved.");
            redirectAttributes.addFlashAttribute("statusError", false);
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("statusMessage", ex.getMessage());
            redirectAttributes.addFlashAttribute("statusError", true);
        }
        return "redirect:/settings";
    }
}
