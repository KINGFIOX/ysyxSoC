/* prefix="sdb_expr": 定义前缀, 命名空间, 避免冲突, yy_scan_string -> sdb_expr_scan_string, etc. */
/* noinput 不生成默认的 yyinput, 避免引用 stdin 读入 */
/* nounput, 不生成 unput, 不引入 unput, 表达式求值一遍扫描就可以了, 不需要回退 */
/* noyywrap, 不引入 yywrap, 字符串是直接传入的, 不需要判断文件结尾 */
/* nodefault, 不使用失败的 default 规则, 而是直接报错, 以免掩盖错误 */
%option noinput nounput noyywrap nodefault prefix="sdb_expr"

%{
#include <stdlib.h>
#include <common.h>
#include <isa.h>
#include "expr.tab.h"

#define yylval sdb_exprlval

extern bool sdb_expr_lexer_error;
extern int sdb_exprerror(const char *msg);

%}

%%
[ \t\r\n]+                ; /* 空白操作符 */
0[xX][0-9a-fA-F]+         { yylval = strtoull(yytext, NULL, 16); return TK_NUM; } /* 十六进制 */
[0-9]+                    { yylval = strtoull(yytext, NULL, 10); return TK_NUM; } /* 十进制 */
\$[a-zA-Z0-9_]+           {
                            bool ok = false;
                            word_t v = isa_reg_str2val(yytext + 1 /* 去掉前缀 $ */ , &ok);
                            if (!ok) { sdb_expr_lexer_error = true; sdb_exprerror("invalid register"); }
                            yylval = v;
                            return TK_REG;
                          }
"=="                      { return EQ; }
"!="                      { return NE; }
"<"                       { return LT; }
"<="                      { return LE; }
">"                       { return GT; }
">="                      { return GE; }
"&&"                      { return AND; }
"||"                      { return OR; }
"+"|"-"|"*"|"/"|"("|")"   { return yytext[0]; } /* 返回符号本身 */
.                         { sdb_expr_lexer_error = true; sdb_exprerror("invalid character"); return yytext[0]; } /* 未知字符 */
%%


