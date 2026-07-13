---
name: create-pr
description: 현재 브랜치의 커밋을 푸시하고, 저장소 PR 템플릿에 맞춰 Pull Request를 작성해 GitHub에 생성한다. 사용자가 "PR 만들어줘", "PR 올려줘", "푸시하고 PR 작성" 등을 요청할 때 사용.
---

# create-pr

현재 작업 브랜치를 원격에 푸시하고, `.github/PULL_REQUEST_TEMPLATE.md`를 따르는 PR을
`develop` 대상으로 생성한다. 커밋/diff에서 내용을 추론해 템플릿을 채운다.

응답과 질문은 모두 **한국어**로 한다.

## 0. 전제조건 확인 (실패 시 중단)

```bash
gh auth status                     # gh 설치·인증
git rev-parse --show-toplevel      # git 저장소 확인
git branch --show-current          # 현재 브랜치
```

- 현재 브랜치가 `develop`/`main`이면 중단하고, 작업 브랜치에서 실행하도록 안내한다.
- **커밋은 이 스킬의 범위가 아니다.** `git status --porcelain` 결과가 있으면(= 커밋 안 된 변경),
  먼저 커밋하도록 안내하고 중단한다. (원치 않으면 사용자가 명시적으로 요청할 때만 커밋)

## 1. 브랜치 · 연결 이슈 파악

- **base 브랜치**: 항상 `develop`.
- **연결 이슈**: 현재 브랜치명이 `<type>/#<번호>` 형식이면 그 번호를 이슈 번호로 사용
  (예: `chore/#189` → `close #189`). 형식이 다르면 커밋 메시지/대화에서 이슈 번호를 추론하고,
  없으면 사용자에게 확인한다.
- **변경 유형**: 브랜치 접두사로 매핑 — `feat`→기능, `fix`→버그, `refactor`→리팩토링,
  `docs`→문서, `chore`→환경설정.

## 2. PR 내용 추론

`develop` 대비 커밋과 diff를 확인해 내용을 채운다.

```bash
git fetch origin
git log --oneline origin/develop..HEAD      # 이 브랜치의 커밋들
git diff --stat origin/develop..HEAD        # 변경 파일 요약
```

- **제목**: 커밋/변경 내용을 바탕으로 간결하게 추론하고 **사용자에게 확인**받는다.
  (저장소는 `feat: ...`와 `[Feat] ...` 스타일이 혼용되므로, 추론한 제목을 제안하고 스타일을 맞춘다.)
- **본문**: 아래 템플릿을 채운다.

## 3. PR 본문 (템플릿 준수)

`.github/PULL_REQUEST_TEMPLATE.md` 구조를 그대로 사용해 채운다.

- **🚀 Summary** — 무엇을/왜 (한두 문장)
- **📝 변경 사항** — 커밋 기반 주요 변경 항목
- **🏷️ 변경 유형** — 브랜치 접두사에 해당하는 체크박스를 `[x]`로 체크
- **✅ 테스트 / 검증 방법** — 검증 방법·실행 결과 (없으면 확인 요청)
- **🖼️ 실행 결과 / 스크린샷** — 없으면 그대로 비워둠
- **🔀 배포 / 마이그레이션 노트** — Flyway 마이그레이션·환경변수 추가 등 있으면 기입, 없으면 `없음`
- **☑️ 체크리스트** — 실제로 충족한 항목만 `[x]` (모르면 비워두고 사용자에게 확인)
- **🎲 관련 이슈** — `close #<이슈번호>`

## 4. 미리보기 & 승인 (필수 게이트)

PR 생성 전에 아래를 보여주고 **명시적 승인**을 받는다 (외부 공유 저장소).

- base ← head (예: `develop ← chore/#189`)
- 제목
- 본문 (채워진 템플릿)
- assignee: 본인(`@me`), 리뷰어: 미지정

## 5. 푸시 & PR 생성

승인 후 진행한다.

```bash
git push -u origin HEAD

# 본문은 임시 파일(스크래치패드)에 저장 후 --body-file 로 전달
gh pr create \
  --base develop \
  --head "<현재 브랜치>" \
  --title "<확인된 제목>" \
  --body-file <임시 본문 파일> \
  --assignee @me
```

- **Ready 상태로 생성**한다 (`--draft` 붙이지 않음).
- 리뷰어는 지정하지 않는다 (GitHub에서 수동 지정).
- 이미 같은 head→base PR이 열려 있으면 새로 만들지 말고 기존 PR URL을 안내한다.

## 6. 결과 보고

- 생성된 PR: 제목 + URL
- base ← head, 연결 이슈(`close #N`)
- 리뷰/머지는 GitHub에서 진행하면 된다는 안내

## 참고

- base는 항상 `develop`. 다른 대상이 필요하면 사용자가 명시할 때만 변경한다.
- 커밋 생성은 범위 밖. 이 스킬은 **이미 커밋된 변경**을 푸시하고 PR을 만든다.
- 관련 이슈 자동 생성/브랜치 분기는 `create-issue` 스킬을 참고.
