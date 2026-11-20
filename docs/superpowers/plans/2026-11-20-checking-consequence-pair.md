# Checking-Consequence Pair Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline and continue without intermediate approval questions as delegated by the user.

**Goal:** Independently prove a closed source-owned two-norm sentence without changing ambiguity or release guards.

**Architecture:** A bounded pure recognizer yields a required checking atom and a conditional permitted atom, retaining the original full literal. SemanticEvidenceMatcher consumes this only for exact source-owned full claims and checks owner-local support plus global conflicts.

**Tech Stack:** Existing Java17 parser/matcher/JUnit; no new dependency or model.

**Spec:** docs/superpowers/specs/2026-11-20-checking-consequence-pair-design.md

## Global Constraints

Keep all frozen questions/source/oracle/scorers, parser mixed-modality guard,
MIN_SLOT_COVERAGE, unsafe gate, previous failed slots, Atlas and paused automation.
One focused review/fix pass and one stable backend suite; Node281 evidence reused
when client code is unchanged. Preserve the immutable old candidate JAR before
packaging any distinct new verified candidate.

## Review Focus

Missing/replaced antecedent; actor/strength/sign changes; detached conclusion;
mixed or annotated source; contradictory global evidence must never become PASS.

### Task 1: Complete source-bound norm pair

**Files:** Create src/main/java/com/kaces/pandora/ai/answer/SourceOwnedCheckingConsequence.java; modify SemanticEvidenceMatcher.java; test CompoundCheckingNormTests.java and ConditionalExampleOverviewTests.java.

**Interfaces:** `parse(String, KoreanEvidenceAtomParser)` returns Optional<Pair> with complete source literal/duty/consequence. Existing atom-only match remains compatible; a raw-text match entrypoint and optional raw source provenance are required for lossless proof (review finding).

- [x] Add failing whole-pair matcher and production-shadow profile tests; expect INSUFFICIENT/unsafe before implementation. Preserve the passing ambiguity characterization.
- [x] Implement the bounded pair recognizer and owner-local/global-conflict checks; all mutations and detached conclusions stay unsupported. Focused285/f0 before review; review correction286/f0/e0.
- [x] One fresh safety review and one fix pass; stable backend2274/f0/e0/skip85. Product5570c301 pushed and officially deployed JARb025e08f with health verified; no source/data/Atlas changes.
- [ ] If authorized per-candidate conditions are satisfied, bind a distinct verified candidate and unchanged payload/source, then once-only penalty→Difficult12→holdout57→full1003 only after prior PASS. Keep every failure immutable; no full completion claim without actual required evidence.
