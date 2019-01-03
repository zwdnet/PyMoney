# -*- coding:utf-8 -*-

#功能程序:实现程序的具体功能
import os
from tools import *


#输入错误时的输出
def ErrorInform(message):
    print(message)
    input("按任意键返回")


#插入新的收入支出项目
def InsertData():
    #print("insertdata")
    os.system("clear")
    time = input("请输入项目时间:")
    if time.isdecimal() == False:
        ErrorInform("请输入八位数字格式的日期")
        return
    if JudgeDate(int(time)) == False:
        ErrorInform("请输入合法的日期19800101-21000101")
        return
    name = input("请输入项目名称:")
    amount = input("请输入项目金额:")
    if amount.isdecimal() == False:
        ErrorInform("请输入数字金额")
        return
    ShowType()
    typeID = input("请输入项目类型ID:")
    print(time, name, amount, typeID)
    input("数据插入完成，按任意键继续。")
    
    
#按项目时间检索数据库
def SearchByTime():
    print("SearchByTime")
    input()
    
    
#按项目类型检索数据库
def SearchByType():
    print("SearchByType")
    input()
    
    
#删除项目
def DelData():
    print("删除项目")
    input()
    
    
#输出现金流量表
def Statics():
    print("现金流量表")
    input()
    