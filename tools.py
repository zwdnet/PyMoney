# -*- coding:utf-8 -*-

#一些其它程序需要使用的工具函数
from DataBase import *
import re


#判断输入的日期是否合法
def JudgeDate(time):
    if time < 19800101 or time > 21000101:
        return False
    MonthDay = time % int(time / 10000)
    Month = int(MonthDay/100)
    Day = MonthDay % 100
    if Month == 0 or Month > 12:
        return False
    if Day == 0:
        return False
    BigMonth = [1, 3, 5, 7, 8, 10, 12]
    if Month in BigMonth:
        if Day > 31:
            return False
    elif Month == 2:
        year = time/10000
        if ((year % 4 == 0 and year % 100 != 0) or (year % 100 == 0 and year % 400 == 0)):
            if Day > 29:
                return False
        else:
            if Day > 28:
                return False
    else:
        if Day > 30:
            return False
    return True
    
    
#输出已有的项目类型信息
def ShowType():
    db = DataBase("money.db")
    sql = "select TypeID from IncomeType"
    db.Execute(sql)
    TypeID = db.GetResult()
    for ID in TypeID:
        sql = "select TypeName from IncomeType where TypeID = "
        sql += str(ID[0])
        db.Execute(sql)
        typename = db.GetResult()
        print("%2d...............%s"%(ID[0], typename[0][0]))
    return len(TypeID)
        

import decimal
# 将元转化为分，都是整数计算，没有误差
def Yuan2Fen(money):
    decimal.getcontext().prec = 4
    #print(money)
    money = money*100.0
    #print(money)
    money = decimal.Decimal.from_float(money)
    #print(money)
    return money
    
  
# 将分转化为元，用于输出
def Fen2Yuan(money):
    money = decimal.Decimal(money)
    decimal.getcontext().prec = 4
    hundred = decimal.Decimal("100.00")
    money =  money/hundred
    return money
    
    
# 判断字符串是否是数字
def IsNumber(num):
    pattern = re.compile(r'^(\-|\+)?\d+(\.\d+)?$')
    result = pattern.match(num)
    if result:
        return True
    else:
        return False
    

#从收入支出项目类型名称中得到项目类型ID
def GetTypeIDbyName(TypeName):
    db = DataBase("money.db")
    sql = "SELECT TypeID FROM IncomeType WHERE TypeName = \""
    sql += TypeName
    sql += "\""
    db.Execute(sql)
    res = db.GetResult()
    if res == []:
        return None
    else:
        return res[0][0]
        
        
#从收入支出项目类型ID中得到项目类型名称
def GetTypeNamebyID(TypeID):
    db = DataBase("money.db")
    sql = "SELECT TypeName FROM IncomeType WHERE TypeID = \""
    sql += str(TypeID)
    sql += "\""
    db.Execute(sql)
    res = db.GetResult()
    if res == []:
        return None
    else:
        return res[0][0]
        
  
#在项目类型表里增加新的项目
def SetType(typename):
    db = DataBase("money.db")
    sql = "INSERT INTO IncomeType VALUES( "
    sql += "NULL"
    sql += " , \""
    sql += typename
    sql += "\")"
    print(sql)
    db.Execute(sql)
    
    
#在收入支出表里增加新的一项
def SetValue(time, name, amount, typeid):
    db = DataBase("money.db")
    sql = "INSERT INTO INCOME VALUES ("
    sql += "NULL"
    sql += " , "
    sql += str(time)
    sql += ",\""
    sql += name
    sql += "\","
    sql += str(amount)
    sql += ","
    sql += str(typeid)
    sql += ")"
    db.Execute(sql)
    
    
#测试插入成功没有
def testInsert(sql):
    db = DataBase("money.db")
    db.Execute(sql)
    res = db.GetResult()
    print(res)
    
    
#输出查询结果
def OutputResult(sql):
    db = DataBase("money.db")
    db.Execute(sql)
    result = db.GetResult()
    for item in result:
        typeName = GetTypeNamebyID(item[4])
        amount = Fen2Yuan(item[3])
        print("项目ID:%d 项目日期:%d 项目名称:%s 项目金额:%.2f 项目类型:%s" % (item[0], item[1], item[2], amount, typeName))
    input("输出查询结果完成，按任意键继续…………")


#输入错误时的输出
def ErrorInform(message):
    print(message)
    input("按任意键返回")
    
    
#让用户输入查询的日期范围
def InputDateRange():
    beginTime = input("请输入起始时间:")
    endTime = input("请输入结束时间:")
    if beginTime.isdecimal() == False or endTime.isdecimal() == False:
        ErrorInform("请输入八位数字格式的日期")
        return (0, 0)
    if JudgeDate(int(beginTime)) == False or JudgeDate(int(endTime)) == False:
        ErrorInform("请输入合法的日期19800101-21000101")
        return (0, 0)
    if beginTime > endTime:
        ErrorInform("结束时间要大于等于开始时间。")
        return  (0, 0)
    return (beginTime, endTime)

    
if __name__ == "__main__":
    """
    while True:
        t = int(input("输入八位时间:"))
        print(JudgeDate(t))
        if t == 0:
            break
    """
    """
    ShowType()
    print(Yuan2Fen(3.54))
    print(0.58)
    print(Yuan2Fen(0.58))
    for i in range(100):
        x = 0.01*i
        f = Yuan2Fen(x)
        y = Fen2Yuan(f)
        print(x, f, y)
    """
    #print(IsNumber("-5.65"))
    #print(IsNumber("6.5"))
    #print(IsNumber("87"))
    #print(IsNumber("-5.6ggg"))
    #name = "工资"
    #print(GetTypeIDbyName(name))
    #ID = 87
    #print(GetTypeNamebyID(ID))
    #SetType("测试")
    #SetValue(20180808, "这是一个测试", 658, 57)
    testInsert("select * from INCOME where TypeID = 57")
    x = Yuan2Fen(65.25)
    print(x)
    print("%.2f" % Fen2Yuan(str(x)))
    #testInsert("select * from Income")
            
    