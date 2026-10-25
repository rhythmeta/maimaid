import Foundation
import Security

/// Extra trust anchors for the SGWCMAID endpoints.
///
/// Both upstreams serve their leaf certificate without the intermediate, so
/// the system trust store cannot build a chain on its own. These are the same
/// public intermediate certificates bundled by the Android app; nothing here
/// is secret. System anchors are still honoured alongside them.
nonisolated enum SgwcmaidTrustAnchors {
  static let pemCertificates: [String] = [
    gameAPIAnchorPEM,
    chimeAnchorPEM
  ]

  static func certificates(from pems: [String]) -> [SecCertificate] {
    pems.compactMap { pem in
      let base64 = pem
        .split(separator: "\n")
        .filter { !$0.contains("-----") }
        .map { $0.trimmingCharacters(in: .whitespaces) }
        .joined()
      guard let data = Data(base64Encoded: base64, options: .ignoreUnknownCharacters) else {
        return nil
      }
      return SecCertificateCreateWithData(nil, data as CFData)
    }
  }

  private static let gameAPIAnchorPEM = """
    -----BEGIN CERTIFICATE-----
    MIIFxjCCBK6gAwIBAgIQDwa7CTBhSCZ/yhtxwduAfTANBgkqhkiG9w0BAQsFADBh
    MQswCQYDVQQGEwJVUzEVMBMGA1UEChMMRGlnaUNlcnQgSW5jMRkwFwYDVQQLExB3
    d3cuZGlnaWNlcnQuY29tMSAwHgYDVQQDExdEaWdpQ2VydCBHbG9iYWwgUm9vdCBH
    MjAeFw0yMjEyMTUwMDAwMDBaFw0zMjEyMTQyMzU5NTlaMFsxCzAJBgNVBAYTAlVT
    MRcwFQYDVQQKEw5EaWdpQ2VydCwgSW5jLjEzMDEGA1UEAxMqR2VvVHJ1c3QgRzIg
    VExTIENOIFJTQTQwOTYgU0hBMjU2IDIwMjIgQ0ExMIICIjANBgkqhkiG9w0BAQEF
    AAOCAg8AMIICCgKCAgEAn1dxOo4OzYa4O1EJd0nhFI5QB/kXAJTHRI6C1J2Gz86B
    Ge9+DD8R4vexG7/QnUvV+5887o+G4enlkDwJV1Pehq4i0n+X6VKIPg5cThCx6/o0
    3bUkLWld7slhi3Hli/MaosZZuytdU1uCzQlGpaLB2TiTZbDImiVaykdfwl8V6AXP
    0Ab4wIcvPggl4qlwyPsBY6NODbP884BmL1ntdXfzecGst30FnAtm4w+PTo0I1T3F
    ITYXaNuIJnKonD1xXaN4Ar/rvcpKpntxVyZQ2T1Lb7vlfYISqNF+1ugpTzKnI1z7
    xV7tSqXd2vs6Csy6yFN+QLWwMnGWuQkvrO1m+F7V85S9ECpbqn9qNtKl8gIQ7JeZ
    BmBdLroW+4j1PDzBWmSWKB+gvRubVSTuoDR3VCtlI4G/Uh+ObF4UStbhKjr1SNCW
    JcrB1oF+wWBl8Bvozm4xflyyGDY47KCP/Fn9X5GHDNLQA9R0zqDKRumipp1UXswF
    uzH0I+gOuqSdZSsYID9XSVq6adcJ3rIMAcTuODcrPrJssOFKGFUbmY/VxbxMgYHI
    /07Zfdxoa+q0osWpofbMhLPZz9UuJFBCLsjgSckkSU02/4itmLm9e3lt6E/4q9Mc
    MCaS8+qrLWKG9wQtviC8zGynEXpxjEQE8WyrfeYKyXwZFCPB4rpTw1joJmGFeLUC
    AwEAAaOCAX4wggF6MBIGA1UdEwEB/wQIMAYBAf8CAQAwHQYDVR0OBBYEFEFOjmmd
    9G4l7HgUHH7XzR2Zz/lrMB8GA1UdIwQYMBaAFE4iVCAYlebjbuYP+vq5Eu0GF485
    MA4GA1UdDwEB/wQEAwIBhjAdBgNVHSUEFjAUBggrBgEFBQcDAQYIKwYBBQUHAwIw
    dAYIKwYBBQUHAQEEaDBmMCMGCCsGAQUFBzABhhdodHRwOi8vb2NzcC5kaWdpY2Vy
    dC5jbjA/BggrBgEFBQcwAoYzaHR0cDovL2NhY2VydHMuZGlnaWNlcnQuY24vRGln
    aUNlcnRHbG9iYWxSb290RzIuY3J0MEAGA1UdHwQ5MDcwNaAzoDGGL2h0dHA6Ly9j
    cmwuZGlnaWNlcnQuY24vRGlnaUNlcnRHbG9iYWxSb290RzIuY3JsMD0GA1UdIAQ2
    MDQwCwYJYIZIAYb9bAIBMAcGBWeBDAEBMAgGBmeBDAECATAIBgZngQwBAgIwCAYG
    Z4EMAQIDMA0GCSqGSIb3DQEBCwUAA4IBAQAkpDVI+nCKnAEEpDfldytHXUYOr2ys
    aGeboE3d1KAN4Gt+eioRvgNdmDKbVFCYY0C6ErIXIvSGXafsprU2dclka/kmH+9U
    4q3v5Acx6KwcnEuYLN0QOtrlQ9s3Z4IbIzdYUv8IauXC27a3x99x2WMVxNu1KGJy
    0r1Z+Xya1adf9/e1LFj1CGdq9HfQ/HFWP2khzxQZ4u62sOHuZdPgtNidFOhQLC+2
    5cNsPgqsaCrGLbSjzni7VN7NhAbTsLhA1oz41vHHB+q4ZzNlC01elmPBOtupe2HM
    /lqMA/J/nTxc718THdxpszFllAET1/lFEolnqihpUFPaPjRwU7yZIseS
    -----END CERTIFICATE-----
  """

  private static let chimeAnchorPEM = """
    -----BEGIN CERTIFICATE-----
    MIIFfjCCA2agAwIBAgIRAIRDWG9jliZDgTN8gBouYRgwDQYJKoZIhvcNAQELBQAw
    RjELMAkGA1UEBhMCQkUxGTAXBgNVBAoTEEdsb2JhbFNpZ24gbnYtc2ExHDAaBgNV
    BAMTE0dsb2JhbFNpZ24gUm9vdCBSNDYwHhcNMjUwOTE3MDI1NTMwWhcNMjkwNjIz
    MDAwMDAwWjBWMQswCQYDVQQGEwJCRTEZMBcGA1UEChMQR2xvYmFsU2lnbiBudi1z
    YTEsMCoGA1UEAxMjR2xvYmFsU2lnbiBHQ0MgUjQ2IEFscGhhU1NMIENBIDIwMjUw
    ggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCV0s/1NfwruxzhrfoOWN/B
    V8j/6KxInyuIpVJ50pmHimyU5ACjC4ST2ZyU2Ltggfc/ydc1OrTUqDyoTGjWOazH
    obK+GQQtBd+MUEykZbnVCfNj14Um7UiqTGOFaOK551Etd9aE/F9/hYl+4XMXJPqC
    +V3ziJbO0QQcL4/wZBvqbW3xZjqb/bcLKHttfNF+JMmO+DrT9VWyomNWVeC3Grs4
    DybZLgs4RVmg8sp/Ic0quOdBjIhE+W0jYbUW6Z9HP9q2lh7UVCwn1rZ1mFTRhIXa
    shGqL43uXnXfRls3T93w2dnCpgTundq28WmWuzb/RZ6kHvALQmu8f891gHfbb+kb
    AgMBAAGjggFVMIIBUTAOBgNVHQ8BAf8EBAMCAYYwEwYDVR0lBAwwCgYIKwYBBQUH
    AwEwEgYDVR0TAQH/BAgwBgEB/wIBADAdBgNVHQ4EFgQUo0yssb+0iO2LdNhfNDjJ
    6QolnnswHwYDVR0jBBgwFoAUA1yrc4GHqMywptWU4jaWSf8FmSwwewYIKwYBBQUH
    AQEEbzBtMC4GCCsGAQUFBzABhiJodHRwOi8vb2NzcC5nbG9iYWxzaWduLmNvbS9y
    b290cjQ2MDsGCCsGAQUFBzAChi9odHRwOi8vc2VjdXJlLmdsb2JhbHNpZ24uY29t
    L2NhY2VydC9yb290cjQ2LmNydDA2BgNVHR8ELzAtMCugKaAnhiVodHRwOi8vY3Js
    Lmdsb2JhbHNpZ24uY29tL3Jvb3RyNDYuY3JsMCEGA1UdIAQaMBgwCAYGZ4EMAQIB
    MAwGCisGAQQBoDIKAQMwDQYJKoZIhvcNAQELBQADggIBAFidiFkGjaslf0o1kWbY
    Y1Fe0N/OtR28cj5js0b6mhb0AXgyi8m3IOBBnHFsyGb/OGpGlsfnyOCNHNc4p12Z
    f8tqkqtd1qh2oks7+MvEAatwDy4NMlQYmjRpdTzTu6+HFv3waK+UOHbm1NC5s5fb
    lPjio082KdjQsG+isWSCUGP7hjVjTcPioy5v0HJDYzmbX1oro7fa7potZ4vjNPRI
    mMH2St+E2OphOO4NkrllXtSUw5ThyiFaymFIvWfSXSWOHIcK3HwOlUxgpgrJMDi0
    ZuKX3W2+wDVRmrPJXgaX+6R/uBqtdMi2O+ebkjmS5zyk2U7sHsaa9lPQz3PS5hBv
    aeW0FHeJK4Yc2yeQ/HBRL3YORG5JQdH1+P/+OJnv7s10Qjipe0tPwHccfMSprzRs
    0t/2wG3b2GdTBX9JJjxWp3SJs/Bib7ScMJYyMMgrUBQ/BSCreEpvKvrsw2SAsPwY
    dx7fjCpsFBM0Tdrqzc1HUm/qNgETPA6tWTMn+27ot19Q94KpnEHYL1hRyCJ5JB/6
    OWVNCbn2YhtwJON6787ZbkVHOz9itAZKajPNH/nO/wB4gtlhnQb1yhZ6nG6LZAsu
    gJBL58/BSqH1KWGnHyp9s7VwFJPI3LoSEqLd5BryAOQg/5P5uW339YFmbJCcqS3G
    3CVvDrQnx+qrRlxBrS/QeigQ
    -----END CERTIFICATE-----
  """
}
