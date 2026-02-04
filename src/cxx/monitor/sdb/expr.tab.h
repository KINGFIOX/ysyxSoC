/* A Bison parser, made by GNU Bison 3.8.2.  */

/* Bison interface for Yacc-like parsers in C

   Copyright (C) 1984, 1989-1990, 2000-2015, 2018-2021 Free Software Foundation,
   Inc.

   This program is free software: you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.

   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with this program.  If not, see <https://www.gnu.org/licenses/>.  */

/* As a special exception, you may create a larger work that contains
   part or all of the Bison parser skeleton and distribute that work
   under terms of your choice, so long as that work isn't itself a
   parser generator using the skeleton or a modified version thereof
   as a parser skeleton.  Alternatively, if you modify or redistribute
   the parser skeleton itself, you may (at your option) remove this
   special exception, which will cause the skeleton and the resulting
   Bison output files to be licensed under the GNU General Public
   License without this special exception.

   This special exception was added by the Free Software Foundation in
   version 2.2 of Bison.  */

/* DO NOT RELY ON FEATURES THAT ARE NOT DOCUMENTED in the manual,
   especially those whose name start with YY_ or yy_.  They are
   private implementation details that can be changed or removed.  */

#ifndef YY_SDB_EXPR_SRC_CXX_MONITOR_SDB_EXPR_TAB_H_INCLUDED
# define YY_SDB_EXPR_SRC_CXX_MONITOR_SDB_EXPR_TAB_H_INCLUDED
/* Debug traces.  */
#ifndef SDB_EXPRDEBUG
# if defined YYDEBUG
#if YYDEBUG
#   define SDB_EXPRDEBUG 1
#  else
#   define SDB_EXPRDEBUG 0
#  endif
# else /* ! defined YYDEBUG */
#  define SDB_EXPRDEBUG 0
# endif /* ! defined YYDEBUG */
#endif  /* ! defined SDB_EXPRDEBUG */
#if SDB_EXPRDEBUG
extern int sdb_exprdebug;
#endif

/* Token kinds.  */
#ifndef SDB_EXPRTOKENTYPE
# define SDB_EXPRTOKENTYPE
  enum sdb_exprtokentype
  {
    SDB_EXPREMPTY = -2,
    SDB_EXPREOF = 0,               /* "end of file"  */
    SDB_EXPRerror = 256,           /* error  */
    SDB_EXPRUNDEF = 257,           /* "invalid token"  */
    TK_NUM = 258,                  /* TK_NUM  */
    TK_REG = 259,                  /* TK_REG  */
    EQ = 260,                      /* EQ  */
    NE = 261,                      /* NE  */
    LT = 262,                      /* LT  */
    LE = 263,                      /* LE  */
    GT = 264,                      /* GT  */
    GE = 265,                      /* GE  */
    AND = 266,                     /* AND  */
    OR = 267,                      /* OR  */
    UMINUS = 268,                  /* UMINUS  */
    DEREF = 269                    /* DEREF  */
  };
  typedef enum sdb_exprtokentype sdb_exprtoken_kind_t;
#endif

/* Value type.  */
#if ! defined SDB_EXPRSTYPE && ! defined SDB_EXPRSTYPE_IS_DECLARED
typedef word_t SDB_EXPRSTYPE;
# define SDB_EXPRSTYPE_IS_TRIVIAL 1
# define SDB_EXPRSTYPE_IS_DECLARED 1
#endif


extern SDB_EXPRSTYPE sdb_exprlval;


int sdb_exprparse (void);


#endif /* !YY_SDB_EXPR_SRC_CXX_MONITOR_SDB_EXPR_TAB_H_INCLUDED  */
