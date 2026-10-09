const fs = require('fs');
global.window = {};
require('./data-interview.js');
require('./data-learn.js');

/* 归一化：根节点用 children，统一改为 c（递归全部子树） */
function norm(n) {
  if (n.children && !n.c) { n.c = n.children; delete n.children; }
  if (!n.t && n.root) n.t = n.root;
  (n.c || []).forEach(norm);
}
norm(window.JVM_INTERVIEW);
norm(window.JVM_LEARN);

const STAR = { 3: '★★★', 2: '★★', 1: '★' };
const strip = s => String(s).replace(/★+/g, '').trim();

function build(D, title, intro) {
  const out = ['# ' + title, '', intro, '---', ''];
  function walk(n, dep) {
    if (dep === 0) { n.c.forEach(k => walk(k, 1)); return; }   /* 跳过根节点自身作为标题 */
    const clean = strip(n.t);
    if (n.c && n.c.length) {
      /* 模块级标题用 ##，三级分类用 ###，知识点叶子用 #### */
      const lv = dep === 1 ? '##' : (dep === 2 ? '###' : '####');
      out.push(lv + ' ' + clean + ' ' + STAR[n.p]);
      out.push('');
      if (n.a) { out.push(n.a); out.push(''); }
      n.c.forEach(k => walk(k, dep + 1));
    } else {
      const lv = dep <= 3 ? '####' : '#####';
      out.push(lv + ' ' + clean + ' ' + STAR[n.p]);
      out.push('');
      out.push(n.a || '');
      out.push('');
    }
  }
  walk(D, 0);
  return out.join('\n');
}

const iIntro = [
  '> **分级说明**：★★★ 必考（约占实际面试提问的 70%，必须能完整背诵）；★★ 高频（需理解原理并能举例）；★ 延伸加分项。',
  '>',
  '> **关于 LeetCode**：LeetCode 主站没有 JVM 专项题目，但其 Concurrency（并发）标签下的 1114/ 1115 / 1116 / 1117 / 1188 / 1195 等题直接考JUC 与 JMM，是国内面试的常见形式，已单独整理在第六模块。'
].join('\n');

const lIntro = [
  '> **说明**：共 9 个阶段，总时长约 13-15 天（每天 3-4 小时有效投入）。每个阶段包含「学习内容 + 动手任务 + 验收标准」。',
  '>',
  '> **权重提示**：阶段 3（GC）与阶段 4（并发）是面试权重最高的两块；阶段 5（排查实战）与阶段 7（项目包装）是真正拉开差距的部分。'
].join('\n');

fs.writeFileSync('Java-JVM面试题答案.md', build(window.JVM_INTERVIEW, 'Java JVM 面试题答案全集', iIntro), 'utf8');
fs.writeFileSync('Java-JVM学习清单.md', build(window.JVM_LEARN, 'Java JVM 学习清单（9阶段路线）', lIntro), 'utf8');

['Java-JVM面试题答案.md', 'Java-JVM学习清单.md'].forEach(f => {
  const s = fs.readFileSync(f, 'utf8');
  const h2 = (s.match(/^## /gm) || []).length;
  const h3 = (s.match(/^### /gm) || []).length;
  console.log(f.padEnd(28), Math.round(s.length / 1024) + 'KB', '|', s.split('\n').length + '行', '| 模块', h2, '| 知识点', h3);
});