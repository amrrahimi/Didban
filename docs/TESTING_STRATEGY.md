# Testing Strategy

## Pyramid
1. **Pure JVM unit tests (bulk).** `core:common`, `core:model`, `core:domain`, future `core:poker`/`core:analytics`. Deterministic, run in milliseconds. Poker engines will be tested against hand-verified fixtures and exhaustive properties (e.g. all 2,598,960 five-card hands produce the known category counts).
2. **Android JVM unit tests.** ViewModels with fake repositories and `Dispatchers.setMain` (`SettingsViewModelTest`), mappers and decoders.
3. **Instrumented tests.** Room DAOs against an in-memory DB (`DaoTest`), schema/migration validation with `MigrationTestHelper` (`MigrationTest`), Compose UI tests on stateless screens (`HomeScreenTest`).
4. **Manual device checklist (each phase).** Camera on LEGACY/LIMITED/FULL devices, permission denial path, rotation, RTL layout in Persian, low-storage import, large video import.

## Current tests
| Suite | Count | Status |
|---|---|---|
| `FrameRateLimiterTest` | 8 | passing |
| `PlayingCardTest`, `GameStateValidatorTest`, `ModelInvariantsTest` | 34 | passing |
| `UseCasesTest` | 8 | passing |
| `SettingsDecoderTest` | 3 | passing |
| `SettingsViewModelTest`, `RoutesTest` | 6 | not yet run |
| `DaoTest`, `MigrationTest`, `HomeScreenTest` (instrumented) | 6 | not yet run |

## Rules
- Every bug fix gets a regression test.
- Vision metrics are only reported from a held-out test set (see ML pipeline); never hand-picked frames.
- Monte Carlo tests use fixed seeds and assert the exact answer lies inside the reported interval.
- Migration tests are mandatory for each schema version bump.
