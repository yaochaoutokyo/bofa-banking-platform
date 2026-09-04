package com.bofa.transactionservice.service;

import com.bofa.transactionservice.model.TransactionException;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OFAC / sanctions list screening for counterparties. Uses normalized
 * name matching with a fuzzy threshold; positive hits block the transfer
 * and are queued for analyst review.
 */
@Service
public class SanctionsScreeningService {

    public static final double MATCH_THRESHOLD = 0.85;

    private final List<ListedParty> watchlist = new ArrayList<>();
    private final Map<String, ScreeningResult> pendingReview = new ConcurrentHashMap<>();

    public SanctionsScreeningService() {
        watchlist.add(new ListedParty("SDN-1001", "Global Trade Holdings Ltd", "SDN", List.of("Global Trading Holdings")));
        watchlist.add(new ListedParty("SDN-1002", "Ivan Petrov", "SDN", List.of("Ivan Petroff", "I. Petrov")));
        watchlist.add(new ListedParty("NS-2001", "Meridian Shipping Co", "NS-PLC", List.of()));
        watchlist.add(new ListedParty("SDN-1003", "Aurora Finance SA", "SDN", List.of("Aurora Financial")));
    }

    public ScreeningResult screen(String transactionId, String counterpartyName, String counterpartyCountry) {
        if (counterpartyName == null || counterpartyName.isBlank()) {
            throw new TransactionException("COUNTERPARTY_REQUIRED", "Counterparty name is required for screening");
        }
        if (isEmbargoed(counterpartyCountry)) {
            ScreeningResult blocked = new ScreeningResult(transactionId, Decision.BLOCKED, null, 1.0,
                    "Embargoed jurisdiction " + counterpartyCountry);
            pendingReview.put(transactionId, blocked);
            return blocked;
        }

        String normalized = normalize(counterpartyName);
        ListedParty bestParty = null;
        double bestScore = 0.0;
        for (ListedParty party : watchlist) {
            double score = similarity(normalized, normalize(party.name()));
            for (String alias : party.aliases()) {
                score = Math.max(score, similarity(normalized, normalize(alias)));
            }
            if (score > bestScore) {
                bestScore = score;
                bestParty = party;
            }
        }

        if (bestScore >= 0.999) {
            ScreeningResult blocked = new ScreeningResult(transactionId, Decision.BLOCKED, bestParty.id(), bestScore,
                    "Exact match on " + bestParty.program());
            pendingReview.put(transactionId, blocked);
            return blocked;
        }
        if (bestScore >= MATCH_THRESHOLD) {
            ScreeningResult review = new ScreeningResult(transactionId, Decision.REVIEW, bestParty.id(), bestScore,
                    "Possible match on " + bestParty.program());
            pendingReview.put(transactionId, review);
            return review;
        }
        return new ScreeningResult(transactionId, Decision.CLEAR, null, bestScore, null);
    }

    public ScreeningResult resolve(String transactionId, boolean truePositive, String analyst) {
        ScreeningResult pending = pendingReview.remove(transactionId);
        if (pending == null) {
            throw new TransactionException("NO_PENDING_REVIEW", "No screening review pending for " + transactionId);
        }
        if (analyst == null || analyst.isBlank()) {
            throw new TransactionException("ANALYST_REQUIRED", "Resolution requires an analyst identity");
        }
        Decision decision = truePositive ? Decision.BLOCKED : Decision.CLEAR;
        return new ScreeningResult(transactionId, decision, pending.matchedListId(), pending.score(),
                "Resolved by " + analyst);
    }

    public List<ScreeningResult> pending() {
        return new ArrayList<>(pendingReview.values());
    }

    static boolean isEmbargoed(String country) {
        if (country == null) {
            return false;
        }
        return switch (country.toUpperCase(Locale.ROOT)) {
            case "KP", "IR", "SY", "CU" -> true;
            default -> false;
        };
    }

    static String normalize(String s) {
        String ascii = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String lower = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ");
        return lower.replaceAll("\\b(ltd|llc|inc|co|corp|sa|plc|gmbh)\\b", "").replaceAll("\\s+", " ").trim();
    }

    /** Normalized Levenshtein similarity in [0,1]. */
    static double similarity(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
            }
        }
        int max = Math.max(a.length(), b.length());
        return max == 0 ? 1.0 : 1.0 - ((double) d[a.length()][b.length()] / max);
    }

    public enum Decision { CLEAR, REVIEW, BLOCKED }

    public record ListedParty(String id, String name, String program, List<String> aliases) {
    }

    public record ScreeningResult(String transactionId, Decision decision, String matchedListId, double score,
                                  String note) {
    }
}
