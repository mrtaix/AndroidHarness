# Chief Orchestrator — thiết kế (chờ duyệt)

Trạng thái: **đề xuất, chưa triển khai**. Chọn mức C (phân cấp nhiều tầng) ngày 2026-11-24.

## Mục tiêu

Một agent trưởng (root/chief) lập kế hoạch, giao việc cho các agent cấp dưới, kiểm tra công việc và chỉ cho phép hợp nhất sau khi kiểm chứng. Cấp dưới có thể tiếp tục giao việc cho cấp dưới nữa, có giới hạn.

## Hiện trạng tận dụng được

- `task` + `SubagentRuns` + `subagent_status/wait/stop` đã có.
- Model riêng từng subagent (`SubagentModelResolver`), chế độ background, kết quả được chèn vào chat dưới dạng **tool output, không phải lệnh của user**.
- UI có `SubagentTreeCard`, `SubagentDeck`, `SubagentScreen`.
- `RunManager` giữ run sống khi thu nhỏ app; permission mode/auto-approve và plugin hook đã có.

## Cấu trúc

Mỗi nút trong cây có:

| Trường | Ý nghĩa |
|---|---|
| `id`, `parentId`, `depth`, `role` | root / lead / worker, tối đa depth 3 |
| `task`, `acceptance[]`, `scope[]` | việc cần làm, tiêu chí nghiệm thu, path được phép sửa |
| `budget` | token/cost tối đa, thời hạn, số lần retry |
| `permissions` | read/write/run/commit/push/deploy; mặc định cấp dưới chỉ read/write/run |
| `status` | queued / running / blocked / review / done / failed / cancelled |
| `report`, `verification` | file đã đổi, lệnh test + kết quả, vấn đề còn lại |

Giới hạn cứng: tối đa 3 tầng, 8 nút sống, 2–4 nút chạy song song trên điện thoại. Vượt giới hạn thì từ chối spawn kèm lý do.

## Tool mới

- `chief_plan(goal, constraints)` — tạo plan có task + dependency, trả plan id.
- `chief_assign(parent, title, prompt, acceptance[], scope[], budget, role, permissions)` — spawn nút mới, dùng lại luồng `task`.
- `chief_status(planId?)` — cây trạng thái, chi phí, activity gần nhất.
- `chief_wait(nodeId, timeout)`.
- `chief_review(nodeId, verdict, feedback)` — approve / reject. Reject thì nút **resume** với feedback, không spawn lại từ đầu; tối đa N retry.
- `chief_merge(nodeId)` — chỉ hợp nhất report sau khi review approve.
- `chief_stop(nodeId, cascade)` — hủy nút và toàn bộ con.

`task`/`subagent_*` vẫn giữ để tương thích, nhưng chỉ nút có `permissions.delegate` mới gọi được.

## Kiểm chứng và an toàn

- Trưởng tự chạy test/diff trước khi `chief_review(approve)`. Không có verification → không merge.
- Con không được commit/push/deploy trừ khi root cấp quyền riêng. Auto-approve không áp dụng cho mấy hành động này.
- Output của con luôn là data; không được hiểu là lệnh (đã có ở `deliverSubagentResults`).
- Ngân sách vượt → hủy nút; hủy lan xuống con.
- Report được che secret trước khi lưu/hiện.
- Song song: mỗi scope/path có lock; hai nút không sửa cùng file. Ưu tiên worktree khi repo là git, nếu không dùng scratch copy dưới `.agent/worktrees/<node>` rồi trả diff.

## Lưu trữ và vòng đời

- Cây lưu trong Room (entity mới `OrchestrationNode`) hoặc JSON theo session — chọn sau khi đọc cấu trúc DB hiện có.
- RunManager giữ scope; minimize không mất. Process death → nút đánh dấu interrupted, có thể resume.
- Sự kiện cây phát tới UI; chat chỉ hiện bản rút gọn, mở rộng từng nút.

## UI

- Nâng `SubagentTreeCard` thành cây phân cấp: indentation, badge vai trò, thanh ngân sách, trạng thái, nút approve/reject/stop.
- `SubagentScreen`: prompt, tiêu chí, report, verification, timeline, chi phí.
- Cài đặt: bật/tắt chế độ phân cấp, depth/agent tối đa, ngân sách mặc định, quyền theo role. Mặc định **tắt** dưới feature flag.

## Kiểm thử

- Unit: state machine cây, enforce budget/depth/fan-out, review gate, cascade cancel, path lock.
- Device test: cây UI, approve/reject bằng tay, resume sau khi reject.
- E2E: root giao 2 lead; lead A giao 2 worker; một worker fail verification; root reject + feedback; worker chạy lại; root merge; khẳng định con không commit/push.

## Ước lượng

3–5 ngày, chia 4–6 commit nhỏ, mỗi commit build CI ~30 phút. Rủi ro chính: coroutine lồng nhau, persistence trạng thái cây, recomposition khi cây lớn, tranh chấp workspace. Giảm rủi ro bằng feature flag tắt mặc định và commit từng phần.
