FROM eclipse-temurin:25-jre

RUN groupadd --system tramai \
    && useradd --system --create-home --gid tramai --uid 10001 tramai \
    && mkdir -p /app /out \
    && chown -R tramai:tramai /app /out \
    && chmod 0777 /out

COPY examples/sovereign-offline-verification/build/install/sovereign-offline-verification/ /app/

USER tramai

ENTRYPOINT ["/app/bin/sovereign-offline-verification", "--report-path=/out/zero-egress-report.json"]
