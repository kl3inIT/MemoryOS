# Verification

- Isolated Redis reproduction: 2s command timeout / 2s BLOCK fails 5/5 reads;
  5s / 2s returns normally 3/3 reads. Same Spring Data Redis/Lettuce versions as staging.
- `gradlew.bat :worker:test --tests "*RedisExecutionTopologyIntegrationTest" :worker:bootJar --no-daemon`: passed, including the new idle-stream test.
- `pnpm exec playwright test --grep FILE`: passed the browser FILE lifecycle (mock API).
- `pnpm build`: production assets and TypeScript check passed.
- Full repository gate and live deployment are not yet verified.
- The abandoned derived-image hotfix was not deployed; live worker/web remained on ee12a21.
