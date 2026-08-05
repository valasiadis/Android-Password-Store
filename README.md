# Password Store

Password Store is a [`pass`](https://www.passwordstore.org/)-compatible **password** manager, **passkey** credential provider and **autofill** service for Android.

As a credential provider, it can respond to passkey creation and authentication requests from browsers and native apps. Supported **passkey types** are **EdDSA (Ed25519)**, **ES256**, and **RS256**. Passkey functionality is available on devices with Android 14 and above.

Forked from the archived [Password Store](https://github.com/android-password-store/Android-Password-Store) project.

[![GitHub workflow](https://github.com/valasiadis/Android-Password-Store/workflows/Deploy%20snapshot%20builds/badge.svg)](https://github.com/valasiadis/Android-Password-Store/actions)

> [!WARNING]
> This repository is a fork of
> [norbusan/Android-Password-Store](https://github.com/norbusan/Android-Password-Store), which in
> turn forks [agrahn/Android-Password-Store](https://github.com/agrahn/Android-Password-Store). It
> implements hardware security support, commit signing and some UI/UX improvements. The features were
> implemented with heavy assistance from generative AI (Claude Opus 4.8 and some OpenAI GPT-5.5) and
> have since undergone review and security hardening upstream, with the goal of eventually merging
> them into agrahn's version. I changed the app ID to `app.passwordstore.valasiadis` for
> compatibility reasons and the app icon color so that the app isn't confused with agrahn's. I will
> also keep this repo up-to-date with the upstream version until the code is merged. If you use this
> version of the app in the meantime, reproducible bug reports and other suggestions would be
> incredibly helpful.

## Download

- Latest [snapshot build (APK)](https://github.com/valasiadis/Android-Password-Store/releases/tag/latest) of this fork
- [GitHub Releases](https://github.com/valasiadis/Android-Password-Store/releases)

You can install this app via [Obtainium](https://obtainium.imranr.dev/) too.

This fork is not on F-Droid. Upstream is, as [`app.passwordstore.agrahn`](https://f-droid.org/en/packages/app.passwordstore.agrahn) — but it has a different app ID and a different signing key, so it cannot be installed as an update over this one.

## Documentation

The original documentation can be found [here](https://docs.passwordstore.app) and [there](https://github.com/android-password-store/Android-Password-Store/wiki/).

To activate passkey (Android 14+) and autofill support, go to Settings → Autofill & Passkeys and choose Password Store as your preferred service. For Chrome and Chromium-based browsers, you might additionally need to enable "Autofill using another service" within the browser's own settings.

Utilising the standard `pass` file structure, passkey data is stored on the first line, followed by optional extra content, as line-oriented plain text secured by PGP encryption. Details on passkey encoding and storage are given in file [`PasskeyStorage.md`](PasskeyStorage.md).

## How-To: Transfer a PGP key to Password Store securely

### From an OpenPGP smartcard

1. Go to `Settings > PGP settings > Key manager > +` and select `Set up NFC smartcard`
2. Present your smartcard behind the phone on the NFC sensor and hold it there

### From GPG keyring
````bash
gpg --armor --gen-random 1 24 # generate a strong random password; use it in the next step
gpg --armor --export-secret-keys <ID of key used for pass> | gpg --armor --symmetric --output myKeyForPass.sec.asc
````
File `myKeyForPass.sec.asc` can be directly imported into Password Store via Settings → PGP Settings → Key Manager → <kbd>+</kbd>; enter the password from the first step when asked for the backup code.

### From OpenKeychain
1. In the main app window, select the key that you use for `pass`/Password Store from the "My Keys" list.
2. In the window that appears, tap the three-dot menu in the top right corner and select "Backup key".
3. Write down the backup code, then save the backup file to your phone.
4. Import this backup file into Password Store by navigating to Settings → PGP Settings → Key Manager → <kbd>+</kbd>, and enter the backup code when prompted.

## Contributing

Issues and pull requests are welcome, but avoid bulky, hard to digest multi-feature contributions, especially AI-generated ones. Refer to the [Changelog](CHANGELOG.md) for the latest fixes and additions.

## Donations

If you wish to sponsor the original author, financial contributions can be made through the following platforms

- [GitHub Sponsors](https://github.com/sponsors/android-password-store)
- [OpenCollective](https://opencollective.com/android-password-store)
