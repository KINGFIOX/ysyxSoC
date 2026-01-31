/**
 * NPC Core 接口定义
 *
 * 该头文件定义了 CPU 核心仿真的公共接口,
 * 由 Verilator 后端 (core.cc) 实现。
 */

#ifndef __CPU_CORE_H__
#define __CPU_CORE_H__

#include <stdbool.h>
#include <stdint.h>

struct Decode;

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 清理 CPU 核心仿真后端
 *
 * 释放 Verilator 模型及相关资源
 */
void npc_core_fini(void);

/**
 * 刷新波形文件
 *
 * 将缓冲区中的波形数据写入文件,
 * 用于异常退出前确保波形完整
 */
void npc_core_flush_trace(void);

/**
 * 执行单条指令
 *
 * 驱动时钟直到 debug.valid 有效,
 * 然后将提交信息写入 Decode 结构体
 *
 * @param s 指向 Decode 结构体的指针, 用于接收:
 *          - s->pc: 当前指令 PC
 *          - s->snpc: 静态下一条指令地址 (pc + 4)
 *          - s->dnpc: 动态下一条指令地址 (实际跳转目标)
 *          - s->isa.inst: 当前指令编码
 *
 * @return true 执行成功, false 执行失败 (超时/错误)
 */
bool npc_core_step(struct Decode *s);

#ifdef __cplusplus
}
#endif

#endif // __CPU_CORE_H__
