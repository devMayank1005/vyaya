# Play Console: Data safety answers (play build)

**Draft for approval.** Play counts data as "collected" only when it is sent off the device. Data processed only on
the device isn't collected ([Play's definitions](https://support.google.com/googleplay/android-developer/answer/10787469)).

| Question | Answer | Why |
|---|---|---|
| Does your app collect or share any of the required user data types? | **No** | The app has no network permission (`scripts/check-release.py` checks every release), so nothing can be sent |
| Is all of the user data collected by your app encrypted in transit? | Not applicable | Nothing is sent |
| Do you provide a way for users to request that their data is deleted? | Not applicable (nothing collected); uninstalling deletes everything on the phone | — |

**Permissions to explain in the listing and in the app** (all on-device):

| Permission | Why | Asked when |
|---|---|---|
| Notification access (`BIND_NOTIFICATION_LISTENER_SERVICE`) | To find payments in UPI, bank and SMS app notifications | The user taps "Allow notification access" on the setup card, which says what is read and that nothing leaves the phone |
| `POST_NOTIFICATIONS` | Budget alerts | When the first budget is saved |
| `USE_BIOMETRIC` | The optional app lock | When the lock is turned on |

**Not in the play build:** `READ_SMS` and `RECEIVE_SMS` (the full build only, outside the Play Store; a manifest test
checks it).

**To check with you:** Play's notification-listener policy expects the listing and the in-app prompt to say clearly
what is read and why. The setup card says "Vyaya reads payment notifications from UPI and bank apps on this phone.
Nothing leaves the phone." The launch break phase looks at whether that is enough.
