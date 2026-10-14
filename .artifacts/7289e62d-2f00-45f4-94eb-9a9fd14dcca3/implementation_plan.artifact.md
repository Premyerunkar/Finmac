# Implementation Plan - Fix Entry Book Navigation

The user is unable to open the "Entry Book" module from the homepage. Investigation revealed that the `HomeFragment` handles the click listener for the dashboard modules but currently only shows a Toast message for "Entry Book" instead of starting the relevant activity.

## Proposed Changes

### [Home]

#### [MODIFY] [HomeFragment.kt](file:///Users/apple/AndroidStudioProjects/Finmac/app/src/main/java/com/sierratechnologies/finmac/HomeFragment.kt)
Update the module click listener to start `VoucherTransactionActivity` when "Entry Book", "Entry", or "Entries" is selected.

### [Adapter]

#### [MODIFY] [ModulesAdapter.kt](file:///Users/apple/AndroidStudioProjects/Finmac/app/src/main/java/com/sierratechnologies/finmac/ModulesAdapter.kt)
Fix typo comments in the internal routing logic to correctly reflect the activities being started (Bank Details, Vendor Details, Entry Book).

## Verification Plan

### Manual Verification
1. Run the app.
2. Navigate to the Home screen.
3. Click on the "Entry Book" module.
4. Verify that `VoucherTransactionActivity` opens correctly.
5. Also check "Entry Book" navigation from the "View All" screen (`AllModulesActivity`) to ensure it still works (as it uses `ModulesAdapter`'s internal routing).
