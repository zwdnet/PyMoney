# -*- coding:utf-8 -*-

#功能程序:实现程序的具体功能
import os
from tools import *


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
    if IsNumber(amount) == False:
        ErrorInform("请输入数字金额")
        return
    amount = Yuan2Fen(float(amount))
    maxnum = ShowType()
    typeID = input("请输入项目类型ID(若新增项目，输0):")
    if typeID.isdigit() == False or int(typeID) > maxnum:
        ErrorInform("请输入正确的项目类型ID,插入新的ID请输0。")
        return
    if int(typeID) == 0:
        #增加项目的类型
        typename = input("请输入要增加的项目名称:")
    else:
        typename = GetTypeNamebyID(typeID)
    typeid = GetTypeIDbyName(typename)
    #print(time, name, amount, typeid, typename)
    if typeid == None:
        SetType(typename)
        typeid = GetTypeIDbyName(typename)
    SetValue(time, name, amount, typeid)
    input("数据插入完成，按任意键继续。")
    
    
#按项目时间检索数据库
def SearchByTime():
    os.system("clear")
    Date = InputDateRange()
    if Date == (0, 0):
        return
    sql = "SELECT * FROM Income where Time >= "
    sql += str(Date[0])
    sql += " and Time <= "
    sql += str(Date[1])
    OutputResult(sql)
    
    
#按项目类型检索数据库
def SearchByType():
    os.system("clear")
    maxnum = ShowType()
    option = input("请输入要查询的项目类型ID:")
    if option.isdigit() == False or int(option) > maxnum or int(option) <= 0:
        ErrorInform("请输入正确的项目类型ID,插入新的ID请输0")
        return
    Date = InputDateRange()
    if Date == (0, 0):
        return
    sql = "SELECT * FROM Income where TypeID = "
    sql += option
    sql += " and Time >= "
    sql += str(Date[0])
    sql += " and Time <= "
    sql += str(Date[1])
    OutputResult(sql)
    
    
#删除项目
def DelData():
    os.system("clear")
    ID = input("即将删除收入支出项目，请输入项目ID，如不知道，请通过软件其它功能查询:")
    sql = "select ID from Income where ID ="
    sql += str(ID)
    db = DataBase("money.db")
    db.Execute(sql)
    res = db.GetResult()
    if res == []:
        input("数据库内无此ID，按任意键返回……")
    else:
        sql = "DELETE FROM INCOME WHERE ID = "
        sql += str(ID)
        db.Execute(sql)
        res = db.GetResult()
        input("删除数据成功，按任意键返回……")
    
    
#输出现金流量表
def Statics():
    print("现金流量表")
    input()
    